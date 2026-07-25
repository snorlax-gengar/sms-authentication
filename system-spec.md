# 프로젝트 명세 및 아키텍처 컨텍스트 (System Spec)

> ⚠️ **AI 에이전트 주의 사항**: 이 파일은 시스템의 유일한 진실 원천(Single Source of Truth)입니다. 구조 변경, API 추가, 라이브러리 추가 발생 시 반드시 이 문서를 최신 상태로 업데이트하세요.

## 1. 기술 스택 및 버전 (Tech Stack)
* **Language**: Java 21
* **Framework**: Spring Boot 3.4.1
* **Build Tool**: Gradle 8.12.1
* **Dependencies**: 
  * Spring Web
  * Spring Data JPA
  * Spring Data Redis
  * Spring Validation
  * Lombok
  * H2 Database (local 프로필 전용 인메모리 RDB)
  * PostgreSQL (`org.postgresql:postgresql`, prod 프로필 RDB)
  * Spring Boot Actuator (헬스체크: `/actuator/health`, `/actuator/health/readiness`)
  * Springdoc OpenAPI 2.8.4 (Swagger UI: `/swagger-ui.html`, API Docs: `/v3/api-docs`)

### 프로필 구성 (Spring Profiles)
* `local` (기본값): H2 인메모리 RDB + 로컬 Redis, `ddl-auto: update`
* `prod`: 외부 PostgreSQL + 외부 Redis, `ddl-auto: validate` (스키마는 `schema.sql`이 소유)
* 활성 프로필은 `SPRING_PROFILES_ACTIVE` 환경 변수로 지정 (미지정 시 `local`)

## 2. 프로젝트 폴더 구조 (Directory Structure)

```
sms-auth/
├── README.md                                 # 프로젝트 소개·실행법·API 예시
├── LICENSE                                   # MIT
├── .github/workflows/ci.yml                  # GitHub Actions (build+test, docker build)
├── build.gradle
├── settings.gradle
├── gradlew
├── Dockerfile                                # 멀티스테이지 빌드 (Gradle → JRE 21 Alpine)
├── docker-compose.yml                        # App + PostgreSQL + Redis 통합 실행
├── .env.example                              # docker-compose용 환경 변수 템플릿
├── .dockerignore
├── src/main/
│   ├── java/com/example/sms/
│   │   ├── SmsAuthApplication.java          # Spring Boot 진입점
│   │   ├── common/
│   │   │   ├── dto/
│   │   │   │   └── ApiResponse.java         # 통일 API 응답 래퍼
│   │   │   └── exception/
│   │   │       ├── SmsException.java
│   │   │       ├── SmsRateLimitException.java
│   │   │       ├── SmsVerificationExpiredException.java
│   │   │       ├── SmsVerificationMismatchException.java
│   │   │       ├── SmsVerificationAttemptExceededException.java  # 검증 시도 횟수 초과 (429)
│   │   │       └── GlobalExceptionHandler.java
│   │   ├── config/
│   │   │   ├── RedisConfig.java             # StringRedisTemplate Bean
│   │   │   ├── OpenApiConfig.java           # Springdoc OpenAPI(Swagger) 문서 설정
│   │   │   ├── JpaAuditingConfig.java       # @EnableJpaAuditing (슬라이스 테스트 호환을 위해 메인 클래스에서 분리)
│   │   │   ├── SmsSenderProperties.java     # SMS 발송 설정 (provider, Solapi credentials)
│   │   │   └── SmsSenderConfig.java         # Solapi RestClient (timeout 3초)
│   │   └── domain/
│   │       └── auth/
│   │           ├── controller/
│   │           │   └── SmsAuthController.java
│   │           ├── service/
│   │           │   └── SmsAuthService.java
│   │           ├── repository/
│   │           │   ├── SmsSendLog.java          # JPA 엔티티
│   │           │   ├── SendStatus.java          # 발송 상태 Enum
│   │           │   ├── SmsSendLogRepository.java
│   │           │   ├── SmsVerificationRepository.java  # Redis 인증번호 저장/조회
│   │           │   └── SmsRateLimitRepository.java     # Redis Rate Limit 카운터
│   │           ├── sender/
│   │           │   ├── SmsSender.java           # 외부 SMS Gateway 추상화
│   │           │   ├── DevSmsSender.java        # 로컬 개발용 (provider=dev, 기본값, 무료)
│   │           │   ├── MockSmsSender.java       # 테스트용 Mock (provider=mock)
│   │           │   └── solapi/
│   │           │       ├── SolapiSmsSender.java       # Solapi 실제 발송 구현체
│   │           │       ├── SolapiAuthHeaderGenerator.java
│   │           │       └── SolapiSendRequest.java
│   │           └── dto/
│   │               ├── SmsRequestDto.java
│   │               └── SmsVerifyDto.java
│   └── resources/
│       ├── application.yml                  # 공통 설정 (프로필 무관: sms 비즈니스 설정, springdoc)
│       ├── application-local.yml            # local 프로필: H2 + 로컬 Redis
│       ├── application-prod.yml             # prod 프로필: PostgreSQL + 외부 Redis
│       └── schema.sql                       # prod 전용 DDL (sms_send_log), sql.init.mode=always일 때만 실행
├── src/test/
│   └── java/com/example/sms/domain/auth/
│       ├── service/
│       │   └── SmsAuthServiceTest.java      # Mockito 순수 유닛 테스트
│       └── controller/
│           └── SmsAuthControllerTest.java   # @WebMvcTest 슬라이스 테스트
└── system-spec.md
```

## 3. 핵심 데이터 모델 및 인프라 (Data Models & Infra)

### RDB (발송 이력 로그)
* **엔티티**: `SmsSendLog`
  * `id` (Long, PK, Auto Increment)
  * `phoneNumber` (String, 11자리)
  * `status` (SendStatus: `SENT` | `FAILED`)
  * `errorMsg` (String, nullable)
  * `createdAt` (LocalDateTime, JPA Auditing)

### Redis (인증 토큰 임시 저장)
* **인증번호 Key**: `sms:verify:{phoneNumber}`
  * Value: `6자리 인증번호`
  * TTL: 180초 (3분)
* **검증 시도 횟수 Key**: `sms:verify:attempt:{phoneNumber}`
  * TTL: 인증번호와 동일(180초), 최대 5회 — 초과 시 인증번호 폐기 (무차별 대입 차단)

### Redis (Rate Limiting)
* **분당 제한 Key**: `sms:rate:minute:{phoneNumber}` — TTL 60초, 최대 3회
* **일일 제한 Key**: `sms:rate:daily:{phoneNumber}` — TTL 24시간, 최대 5회

### 환경 변수

#### 공통
| 변수 | 기본값 | 설명 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `local` | 활성 프로필 (`local` \| `prod`) |
| `SERVER_PORT` | `8080` | 서버 포트 |
| `SMS_SENDER_PROVIDER` | `dev` | SMS 발송 구현체 (`dev` \| `mock` \| `solapi`) |
| `SMS_FROM_NUMBER` | — | Solapi 발신번호 (사전 등록 필수, 예: `01012345678`) |
| `SOLAPI_API_KEY` | — | Solapi API Key |
| `SOLAPI_API_SECRET` | — | Solapi API Secret |
| `SOLAPI_BASE_URL` | `https://api.solapi.com` | Solapi API Base URL |

#### `local` 프로필 (H2 + 로컬 Redis)
| 변수 | 기본값 | 설명 |
|------|--------|------|
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |

#### `prod` 프로필 (PostgreSQL + 외부 Redis)
| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_HOST` | — (필수) | PostgreSQL 호스트 |
| `DB_PORT` | `5432` | PostgreSQL 포트 |
| `DB_NAME` | — (필수) | PostgreSQL 데이터베이스명 |
| `DB_USERNAME` | — (필수) | PostgreSQL 사용자명 |
| `DB_PASSWORD` | — (필수) | PostgreSQL 비밀번호 |
| `DB_POOL_SIZE` | `10` | HikariCP 최대 커넥션 수 |
| `DB_POOL_MIN_IDLE` | `2` | HikariCP 최소 유휴 커넥션 수 |
| `REDIS_HOST` | — (필수) | 외부 Redis 호스트 |
| `REDIS_PORT` | `6379` | 외부 Redis 포트 |
| `REDIS_PASSWORD` | — | 외부 Redis 비밀번호 (미설정 시 비인증) |
| `REDIS_SSL_ENABLED` | `false` | 외부 Redis TLS 연결 여부 |
| `REDIS_TIMEOUT_MS` | `3000` | Redis 커맨드 타임아웃(ms) |

> `prod` 프로필의 `DB_*`, `REDIS_HOST`는 기본값이 없어, 값 누락 시 애플리케이션 기동 단계에서 즉시 실패한다(fail-fast).

### SMS 발송 구현체 (SmsSender)
| Provider | 클래스 | 비용 | 설명 |
|----------|--------|------|------|
| `dev` (기본) | `DevSmsSender` | 무료 | 콘솔에 인증번호 출력 — 로컬 개발·플로우 테스트용 |
| `mock` | `MockSmsSender` | 무료 | 발송 성공만 기록, 인증번호 미출력 — CI/단위 테스트용 |
| `solapi` | `SolapiSmsSender` | 유료 | Solapi REST API 연동, 실제 휴대폰 문자 발송 |

## 4. API 엔드포인트 명세 (API Specifications)

### `POST` /api/v1/sms/request
* **설명**: 인증번호 생성 및 SMS 발송 요청
* **Request Body**:
```json
{ 
  "phoneNumber": "01012345678" 
}
```
* **Response (200 OK)**:
```json
{ 
  "success": true, 
  "message": "인증번호가 발송되었습니다." 
}
```
* **Error Responses**:
  * `400` — 잘못된 전화번호 형식 (`INVALID_REQUEST`)
  * `429` — Rate Limit 초과 (`SMS_RATE_LIMIT_EXCEEDED`)
  * `502` — SMS Gateway 발송 실패 (`SMS_SEND_FAILED`)

### `POST` /api/v1/sms/verify
* **설명**: 인증번호 검증
* **Request Body**:
```json
{ 
  "phoneNumber": "01012345678", 
  "code": "123456" 
}
```
* **Response (200 OK)**:
```json
{
  "success": true,
  "message": "인증에 성공했습니다."
}
```
* **Error Responses**:
  * `400` — 인증번호 만료/미존재 (`SMS_VERIFICATION_EXPIRED`)
  * `400` — 인증번호 불일치 (`SMS_VERIFICATION_MISMATCH`)
  * `429` — 검증 시도 횟수(5회) 초과, 인증번호 폐기됨 (`SMS_VERIFICATION_ATTEMPT_EXCEEDED`)

### 공통 에러 응답 형식
```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "에러 메시지"
  }
}
```

## 5. 비즈니스 흐름 요약

1. **인증 요청**: Rate Limit 검증(`INCR` 반환값으로 원자적 판정) → 6자리 난수 생성 → Redis 저장(TTL 180s) → 시도 횟수 카운터 초기화 → SMS 발송(동기, 타임아웃 3초) → RDB 발송 이력 기록(SENT/FAILED)
2. **인증 확인**: Redis 조회 → 상수 시간 비교 → 불일치 시 시도 횟수 증가(5회 도달 시 인증번호 폐기) → 성공 시 인증번호·카운터 삭제(재사용 방지)

### 트랜잭션·동시성 설계 결정
* `requestVerification`은 **의도적으로 `@Transactional`을 사용하지 않는다**. 외부 HTTP 호출 구간을 트랜잭션으로 감싸면 (1) 응답 대기 중 DB 커넥션을 점유하고, (2) 발송 실패 시 예외 전파와 함께 FAILED 이력까지 롤백되어 장애 분석 데이터가 유실된다. 발송 이력은 단건 INSERT이므로 `JpaRepository.save()` 자체 트랜잭션으로 충분하다.
* Rate Limit은 조회 후 증가(check-then-act)가 아닌 **`INCR` 반환값으로 즉시 판정**한다. 동시 요청 시 제한을 초과해 통과하는 경쟁 조건을 방지하기 위함이다. 카운터를 먼저 올리므로 판정 기준은 `> max`이다.
* SMS 발송은 **동기 방식**이다. `@Async`로 처리하면 예외가 호출자에게 전파되지 않아 `502 SMS_SEND_FAILED` 응답 규격을 지킬 수 없다.

## 6. 컨테이너화 (Docker)

### Dockerfile
* 멀티스테이지 빌드: `eclipse-temurin:21-jdk-alpine`(빌드) → `eclipse-temurin:21-jre-alpine`(런타임)
* 빌드 산출물은 `build.gradle`에서 `app.jar`로 고정 (plain jar 비활성화)
* non-root 사용자(`spring`)로 실행, `HEALTHCHECK`는 `/v3/api-docs`(springdoc) 응답으로 판단
* 기본 `SPRING_PROFILES_ACTIVE=prod` — 컨테이너 실행 시 항상 외부 PostgreSQL/Redis를 사용

### docker-compose.yml
* 서비스: `app`(빌드된 이미지) + `db`(`postgres:16-alpine`) + `redis`(`redis:7-alpine`)
* `app`은 `db`/`redis`의 헬스체크 통과 후(`depends_on: condition: service_healthy`) 기동
* `db`, `redis`는 각각 named volume(`sms-auth-db-data`, `sms-auth-redis-data`)으로 데이터 영속화
* Redis는 `REDIS_PASSWORD`가 비어 있으면 무인증(로컬 기본값), 값이 있으면 `requirepass`로 기동(운영)
* 환경 변수는 `.env` 파일(`.env.example` 참고)로 주입 — 로컬은 예시값 그대로, 운영은 실제 시크릿으로 교체
* 사용법: `cp .env.example .env` → 값 채우기 → `docker compose up -d --build`
* 운영 배포 시 권장: `db.ports`(5432 호스트 노출) 제거, `.env`는 시크릿 매니저/CI 변수로 주입하고 git에 커밋하지 않음

---

## 6. 변경 이력 (Changelog)
* `2026-07-25`: 프로젝트 초기 구조 설정 및 Redis 연동 명세 확정.
* `2026-07-25`: 초기 뼈대 코드 생성 — SmsSendLog 엔티티/JPA, Redis 인증·Rate Limit Repository, SmsSender/MockSmsSender, SmsAuthService, SmsAuthController, 공통 예외/GlobalExceptionHandler, Gradle 빌드 설정.
* `2026-07-25`: Solapi 실제 SMS 발송 구현체(`SolapiSmsSender`) 추가 — `SMS_SENDER_PROVIDER=solapi`로 전환 시 실제 문자 발송.
* `2026-07-25`: 무료 로컬 개발용 `DevSmsSender` 추가 — 기본 provider를 `dev`로 변경, 콘솔에 인증번호 출력.
* `2026-07-25`: Springdoc OpenAPI(Swagger) 도입 — `OpenApiConfig`, Controller/DTO 문서화 어노테이션, `/swagger-ui.html` 노출.
* `2026-07-25`: `SmsAuthService` 유닛 테스트 및 `SmsAuthController` `@WebMvcTest` 슬라이스 테스트 추가 (정상 흐름 + Rate Limit/만료/불일치/입력값 검증 엣지 케이스).
* `2026-07-25`: `@EnableJpaAuditing`을 `SmsAuthApplication`에서 `JpaAuditingConfig`로 분리 — `@WebMvcTest` 슬라이스 컨텍스트에서 `jpaAuditingHandler` 빈 생성 실패(jpaMappingContext 참조 불가) 버그 수정.
* `2026-07-25`: `application.yml`을 공통/`local`(H2)/`prod`(PostgreSQL+외부 Redis) 프로필로 분리, PostgreSQL 드라이버 의존성 추가, `SmsSendLog` 엔티티 기준 `schema.sql`(PostgreSQL DDL) 추가 — prod는 `ddl-auto: validate` + `sql.init.mode: always`로 스키마를 `schema.sql`이 소유.
* `2026-07-25`: 컨테이너화 — 멀티스테이지 `Dockerfile`(Gradle 빌드 → JRE 21 Alpine 런타임, non-root, HEALTHCHECK) 및 App+PostgreSQL+Redis `docker-compose.yml`, `.env.example`, `.dockerignore` 추가. `build.gradle`에 `bootJar` 산출물명을 `app.jar`로 고정.
* `2026-07-25`: **버그 수정 3건** — (1) `requestVerification`의 `@Transactional` 제거: 예외 전파 시 FAILED 발송 이력이 롤백되어 유실되던 문제 해결, (2) 인증번호 검증 시도 횟수 제한(5회) 도입: 6자리 코드에 대한 무차별 대입 차단, `SmsVerificationAttemptExceededException`(429) 추가, (3) Rate Limit을 `INCR` 반환값 기반으로 변경: check-then-act 경쟁 조건 해소.
* `2026-07-25`: 인증번호 비교를 `MessageDigest.isEqual` 상수 시간 비교로 변경, 로그 전화번호 마스킹을 길이 검사 포함 방어적 구현으로 교체(`StringIndexOutOfBoundsException` 위험 제거).
* `2026-07-25`: `@Async` 명세 불일치 정리 — 미사용 `AsyncConfig`(死코드) 및 `@EnableAsync` 제거, 발송은 동기 방식으로 문서 확정.
* `2026-07-25`: Spring Boot Actuator 도입 — `/actuator/health/readiness`(DB·Redis 포함)로 Dockerfile·compose 헬스체크 교체(기존 `/v3/api-docs` 편법 대체).
* `2026-07-25`: 공개 레포 준비 — `README.md`(아키텍처·실행법·API 예시·설계 노트), `LICENSE`(MIT), GitHub Actions CI(`build+test`, Docker 빌드 검증) 추가.
