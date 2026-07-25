# sms-auth

<!-- TODO: {OWNER}/{REPO}를 실제 GitHub 경로로 바꾸면 배지가 활성화됩니다 -->
[![CI](https://github.com/{OWNER}/{REPO}/actions/workflows/ci.yml/badge.svg)](https://github.com/{OWNER}/{REPO}/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Redis TTL과 Rate Limiting을 활용한 SMS 인증번호 발송·검증 API.

Spring Boot 3.4 기반이며, 인증번호는 Redis에 3분 TTL로 저장하고 발송 이력은 RDB에 남깁니다. 외부 SMS Gateway는 인터페이스로 추상화되어 있어 로컬 개발 시 실제 발송 비용 없이 전체 플로우를 검증할 수 있습니다.

## 주요 기능

- **인증번호 발송**: 6자리 난수 생성(`SecureRandom`) → Redis 저장(TTL 180초) → SMS 발송 → 발송 이력 기록
- **인증번호 검증**: 상수 시간 비교 후 성공 시 즉시 폐기(재사용 방지)
- **다층 Rate Limiting**: 발송은 분당 3회·일일 5회, 검증은 인증번호당 5회로 제한하여 무차별 대입 차단
- **발송 구현체 교체**: 환경 변수 하나로 `dev`(콘솔 출력) / `mock` / `solapi`(실제 발송) 전환
- **일관된 응답 규격**: 성공·실패 모두 동일한 JSON 래퍼, 도메인별 에러 코드 제공

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.1, Spring Data JPA, Spring Data Redis |
| Database | PostgreSQL (prod), H2 (local) |
| Cache | Redis |
| Docs | Springdoc OpenAPI 2.8.4 |
| Test | JUnit 5, Mockito, AssertJ |
| Build | Gradle 8.12.1 |
| Infra | Docker, Docker Compose |

## 아키텍처

```
Client
  │
  ▼
SmsAuthController ──── @Valid (전화번호·인증번호 형식 검증)
  │
  ▼
SmsAuthService ──────── Rate Limit 검증 → 코드 생성 → 저장 → 발송
  │
  ├─▶ SmsVerificationRepository ─┐
  ├─▶ SmsRateLimitRepository ────┼─▶ Redis   (인증번호 TTL, 카운터)
  │                              ┘
  ├─▶ SmsSendLogRepository ────────▶ RDB     (발송 이력)
  │
  └─▶ SmsSender (interface)
         ├─ DevSmsSender     (콘솔 출력, 무료)
         ├─ MockSmsSender    (기록만, CI용)
         └─ SolapiSmsSender  (실제 발송, HMAC-SHA256 인증)
```

Redis 키 구조:

| 키 | TTL | 용도 |
|----|-----|------|
| `sms:verify:{phone}` | 180초 | 인증번호 |
| `sms:verify:attempt:{phone}` | 180초 | 검증 실패 횟수 |
| `sms:rate:minute:{phone}` | 60초 | 분당 발송 횟수 |
| `sms:rate:daily:{phone}` | 24시간 | 일일 발송 횟수 |

## 시작하기

### Docker Compose (App + PostgreSQL + Redis)

```bash
cp .env.example .env     # 필요 시 값 수정
docker compose up -d --build
```

### 로컬 실행 (H2 + 로컬 Redis)

Redis만 있으면 됩니다.

```bash
docker run -d -p 6379:6379 redis:7-alpine
./gradlew bootRun        # 기본 프로필: local
```

`SMS_SENDER_PROVIDER`의 기본값이 `dev`이므로 실제 문자는 발송되지 않고 인증번호가 콘솔에 출력됩니다.

### 확인

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- H2 Console (local 전용): http://localhost:8080/h2-console

## API

### 인증번호 발송

```bash
curl -X POST http://localhost:8080/api/v1/sms/request \
  -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"01012345678"}'
```

```json
{ "success": true, "message": "인증번호가 발송되었습니다." }
```

### 인증번호 검증

```bash
curl -X POST http://localhost:8080/api/v1/sms/verify \
  -H 'Content-Type: application/json' \
  -d '{"phoneNumber":"01012345678","code":"123456"}'
```

```json
{ "success": true, "message": "인증에 성공했습니다." }
```

### 에러 응답

```json
{
  "success": false,
  "error": { "code": "SMS_RATE_LIMIT_EXCEEDED", "message": "1분 내 최대 요청 횟수(3회)를 초과했습니다." }
}
```

| HTTP | 에러 코드 | 상황 |
|------|-----------|------|
| 400 | `INVALID_REQUEST` | 전화번호·인증번호 형식 오류 |
| 400 | `SMS_VERIFICATION_EXPIRED` | 인증번호 만료 또는 미존재 |
| 400 | `SMS_VERIFICATION_MISMATCH` | 인증번호 불일치 |
| 429 | `SMS_RATE_LIMIT_EXCEEDED` | 발송 요청 제한 초과 |
| 429 | `SMS_VERIFICATION_ATTEMPT_EXCEEDED` | 검증 시도 횟수 초과 (인증번호 폐기) |
| 502 | `SMS_SEND_FAILED` | SMS Gateway 발송 실패 |

## 설정

프로필은 `SPRING_PROFILES_ACTIVE`로 전환합니다 (`local` 기본값 / `prod`).

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `local` | 활성 프로필 |
| `SMS_SENDER_PROVIDER` | `dev` | `dev` \| `mock` \| `solapi` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 접속 정보 |
| `DB_HOST` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | — | prod 전용, 미설정 시 기동 실패 |
| `SOLAPI_API_KEY` / `SOLAPI_API_SECRET` / `SMS_FROM_NUMBER` | — | `provider=solapi`일 때 필수 |

전체 목록은 [`system-spec.md`](system-spec.md)를 참고하세요.

## 테스트

```bash
./gradlew test
```

- `SmsAuthServiceTest` — Mockito 기반 순수 유닛 테스트 (Rate Limit 경계값, 시도 횟수 초과, 발송 실패 이력 등)
- `SmsAuthControllerTest` — `@WebMvcTest` 슬라이스 테스트 (입력값 검증, HTTP 상태 코드, 에러 코드)

## 설계 노트

**발송 요청에 `@Transactional`을 사용하지 않습니다.** 외부 HTTP 호출이 포함된 구간을 트랜잭션으로 감싸면 응답 대기 중 DB 커넥션을 점유하고, 발송 실패 시 예외 전파와 함께 FAILED 이력까지 롤백되어 장애 분석 데이터가 사라집니다. 발송 이력은 단건 INSERT이므로 `JpaRepository.save()` 자체 트랜잭션으로 충분합니다.

**Rate Limit은 `INCR` 반환값으로 판정합니다.** 조회 후 증가시키는 방식은 동시 요청 시 제한을 넘겨 통과하는 경쟁 조건이 있습니다.

**`@EnableJpaAuditing`은 별도 설정 클래스로 분리했습니다.** 메인 애플리케이션 클래스에 두면 `@WebMvcTest` 슬라이스 컨텍스트가 JPA 자동 구성 없이 `jpaAuditingHandler` 빈을 만들려다 실패합니다.

## 라이선스

[MIT](LICENSE)
