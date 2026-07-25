# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 문서 체계

이 저장소에는 세 종류의 문서가 있으며 역할이 다르다.

| 문서 | 역할 |
|------|------|
| `system-spec.md` | **유일한 진실 원천(SSOT)**. 구조·API·의존성·환경 변수의 최신 상태 |
| `.cursor/.rules/*.md` | 코딩 스탠다드 및 도메인 설계 규칙 (경로가 `.cursor/rules/`가 아닌 `.cursor/.rules/`임에 주의) |
| `README.md` | 외부 공개용 소개 — 아키텍처 개요, 실행법, API 예시 |

### 필수 규칙: system-spec.md 동기화

새 의존성 추가, 도메인/엔티티/API 엔드포인트 구현, 폴더 구조 변경 시 **반드시** `system-spec.md`를 함께 갱신한다. 코드 수정을 마친 마지막 단계에 변경 내역(§6 변경 이력 포함)을 반영하고, 무엇이 업데이트됐는지 사용자에게 요약 보고한다. 이는 `.cursor/.rules/global-standards.md`에 명시된 강제 규칙이다.

## 명령어

```bash
./gradlew test                                          # 전체 테스트 (통합 테스트 포함 — Docker 필요)
./gradlew test -PexcludeIntegration                     # Docker 없이 유닛/슬라이스만
./gradlew test --tests '*SmsAuthServiceTest'            # 특정 클래스
./gradlew test --tests '*SmsAuthServiceTest$Verify'     # 특정 @Nested 그룹
./gradlew test --tests '*SmsAuthServiceTest$Verify.success'   # 특정 메서드
                                                        # 메서드명이 중첩 클래스 간 중복되므로
                                                        # (success 등) $그룹 지정이 필요하다
./gradlew build                                         # 컴파일 + 테스트 + bootJar
./gradlew bootRun                                       # 로컬 실행 (local 프로필, Redis 필요)
```

리포트: 테스트 `build/reports/tests/test/index.html`, 커버리지 `build/reports/jacoco/test/html/index.html`

`SmsAuthIntegrationTest`는 `@Tag("integration")`이 붙어 있고 Testcontainers로 실제 PostgreSQL·Redis를 띄운다. **`prod` 프로필로 실행되므로 `schema.sql` ↔ 엔티티 정합성 검증을 겸한다** — 엔티티를 바꾸고 `schema.sql`을 안 고치면 이 테스트가 컨텍스트 로딩 단계에서 실패한다.

로컬 실행에는 Redis만 있으면 된다 (`docker run -d -p 6379:6379 redis:7-alpine`). RDB는 H2 인메모리이고, `SMS_SENDER_PROVIDER` 기본값이 `dev`라 실제 문자는 발송되지 않고 콘솔에 인증번호가 출력된다.

전체 스택 실행:

```bash
cp .env.example .env && docker compose up -d --build     # App + PostgreSQL + Redis
```

## 아키텍처

### 도메인 흐름

SMS 인증은 **요청(Request) → 발송(Send) → 확인(Verify)** 세 단계로 분리되어 있다.

- `SmsAuthController` → `SmsAuthService` → `*Repository` / `SmsSender`
- Service 레이어는 HTTP 스펙(`HttpServletRequest`, `ResponseEntity` 등)에 의존하지 않는다.
- 외부 SMS Gateway는 `SmsSender` 인터페이스로 추상화되어 있고, 구현체는 `sms.sender.provider` 값에 따라 `@ConditionalOnProperty`로 **하나만** 빈 등록된다 (`dev` / `mock` / `solapi`).

### 저장소 이원화

| 저장소 | 용도 | 클래스 |
|--------|------|--------|
| Redis | 인증번호(TTL 180s), 검증 시도 횟수, Rate Limit 카운터 | `SmsVerificationRepository`, `SmsRateLimitRepository` |
| RDB | 발송 이력 로그 (SENT/FAILED) | `SmsSendLogRepository` (JPA) |

Redis 키는 모두 `sms:` 프리픽스를 쓰며 전체 목록은 `system-spec.md` §3에 있다. Redis 접근은 `StringRedisTemplate` 직접 사용 방식이다(Spring Data Redis Repository 아님).

### 에러 처리 구조

모든 도메인 예외는 `SmsException`(`errorCode` 보유)을 상속하고, `GlobalExceptionHandler`가 예외 타입별로 HTTP 상태 코드에 매핑한다. 새 예외를 추가하면 **핸들러 등록도 함께** 해야 한다 — 등록하지 않으면 `SmsException` 폴백에 걸려 의도치 않게 500이 반환된다.

응답은 성공·실패 모두 `ApiResponse<T>` 래퍼로 통일한다 (`success` / `data` / `message` / `error{code,message}`).

## 변경 시 주의할 설계 결정

아래는 모두 **의도적인 선택**이며, 되돌리면 과거에 수정한 버그가 재발한다. 변경 전 반드시 근거를 확인할 것.

**`SmsAuthService.requestVerification`에 `@Transactional`을 붙이지 말 것.**
이 메서드는 Redis 쓰기와 외부 HTTP 호출을 포함한다. 트랜잭션으로 감싸면 (1) 외부 API 응답 대기 중 DB 커넥션을 점유하고, (2) 발송 실패 시 예외 전파와 함께 catch 블록의 FAILED 이력 저장까지 롤백되어 장애 분석 데이터가 유실된다. 발송 이력은 단건 INSERT이므로 `JpaRepository.save()` 자체 트랜잭션으로 충분하다.

**Rate Limit은 `INCR` 반환값으로 판정한다.**
조회 후 증가시키는 check-then-act 방식은 동시 요청 시 제한을 초과해 통과하는 경쟁 조건이 있다. 카운터를 먼저 올리므로 판정 기준은 `>= max`가 아니라 `> max`다.

**SMS 발송은 동기 방식이다. `@Async`를 도입하지 말 것.**
비동기로 처리하면 예외가 호출자에게 전파되지 않아 `502 SMS_SEND_FAILED` 응답 규격을 지킬 수 없다. 외부 호출 안전장치는 타임아웃(3초, `SmsSenderConfig`)으로 확보한다. `.cursor/.rules/sms-architecture.md`는 "비동기 **또는** 타임아웃"을 요구하므로 이 선택은 규칙을 충족한다.

**`@EnableJpaAuditing`은 `JpaAuditingConfig`에 두고 `SmsAuthApplication`으로 옮기지 말 것.**
`@WebMvcTest`는 메인 애플리케이션 클래스를 컨텍스트 설정 소스로 사용하므로, 메인 클래스에 있으면 JPA 자동 구성이 빠진 슬라이스 컨텍스트에서 `jpaAuditingHandler` 빈 생성이 실패한다(`jpaMappingContext` 참조 불가).

**`bootJar`의 `archiveFileName = 'app.jar'`를 변경하지 말 것.** `Dockerfile`이 이 경로를 직접 참조한다.

## 프로필 및 설정

`SPRING_PROFILES_ACTIVE`로 전환하며 기본값은 `local`이다.

- `local` — H2 인메모리 + 로컬 Redis, `ddl-auto: update`, `sql.init.mode: never`
- `prod` — 외부 PostgreSQL + 외부 Redis, `ddl-auto: validate`, `sql.init.mode: always`

**prod에서는 `schema.sql`이 스키마의 소유자다.** 엔티티(`SmsSendLog`)를 변경하면 `src/main/resources/schema.sql`의 DDL도 함께 수정해야 한다. `ddl-auto: validate`이므로 불일치 시 기동 단계에서 실패한다.

`prod` 프로필의 `DB_HOST`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`/`REDIS_HOST`는 기본값이 없어 누락 시 즉시 기동 실패한다(fail-fast, 의도된 동작).

## 테스트 규약

`.cursor/.rules/testing-principles.md` 기준:

- 모든 테스트는 **Given-When-Then**을 주석으로 명확히 구분한다.
- Service는 외부 의존성을 Mocking한 **순수 유닛 테스트**(`@ExtendWith(MockitoExtension.class)`)로 작성한다. `@Value` 필드는 주입되지 않으므로 `ReflectionTestUtils.setField`로 세팅한다.
- Controller는 `@WebMvcTest` + `@MockitoBean`(Spring Boot 3.4 방식, `@MockBean`은 deprecated)으로 HTTP 상태 코드와 에러 코드를 검증한다.
- `@Nested` + `@DisplayName`으로 한국어 시나리오를 기술한다.
- 정상 흐름뿐 아니라 **잘못된 입력값, 만료된 인증번호, Rate Limit 초과**를 반드시 포함한다. 경계값(제한 직전/직후) 케이스도 함께 작성한다.

## 보안 관련 불변 사항

- 인증번호 원본을 로그에 남기지 않는다. 전화번호는 `mask()`로 뒤 4자리만 남긴다 (길이 검사 포함 — 형식이 어긋난 입력에서 `substring`이 터지지 않도록).
- 인증번호 비교는 `MessageDigest.isEqual` 상수 시간 비교를 사용한다.
- 인증 성공 또는 시도 횟수 초과 시 Redis 키를 즉시 삭제해 재사용·무차별 대입을 차단한다.
- 자격 증명은 환경 변수로만 주입한다. `.env`는 gitignore 대상이며 `.env.example`만 커밋한다.
