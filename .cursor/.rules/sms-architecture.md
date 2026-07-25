---
description: "Java Spring Boot 기반 SMS 인증 시스템의 도메인 설계 및 흐름 제어 규칙"
alwaysApply: false
---

## 1. 기술 스택 및 핵심 라이브러리
* **Framework**: Spring Boot 3.x, Spring Data JPA
* **Database/Cache**: Redis (인증번호 TTL 및 Rate Limiting 관리 목적), RDB (발송 이력 로그 및 정산용)
* **Test**: JUnit 5, Mockito

## 2. 도메인 분리 및 흐름 규칙
SMS 인증 프로세스는 다음 세 단계로 명확히 분리하여 구현합니다.

1. **인증 요청 (Request)**
   * 클라이언트가 핸드폰 번호를 전달하면, 시스템은 6자리 난수를 생성합니다.
   * 생성된 인증번호는 Redis에 `휴대폰번호 : 인증번호` 형태로 저장하며, **TTL(만료 시간)은 3분(180초)**으로 설정합니다.
   * 동일 번호로 단시간 내 반복 요청 시 차단하는 **Rate Limit**(예: 1분에 최대 3회, 일일 최대 5회) 로직을 Service 레이어 진입 시점에 검증합니다.

2. **발송 (Send - External API)**
   * 외부 통신사 API(SMS Gateway) 호출부는 `SmsSender` 인터페이스로 추상화하고, 실제 연동부(`CoolSmsSender`, `SureMSender` 등)는 구현체로 분리하여 OCP(개방-폐쇄 원칙)를 준수합니다.
   * 외부 API 호출은 비동기(`@Async`)로 처리하거나 타임아웃(최대 3초)을 명확히 설정합니다.

3. **인증 확인 (Verify)**
   * 클라이언트가 제출한 인증번호와 Redis의 값을 비교합니다.
   * 인증 성공 시 Redis 데이터를 즉시 삭제(혹은 완료 마킹)하여 재사용을 방지합니다.

## 3. 예외 처리 스탠다드 (Exception)
* `SmsException`을 상속받는 커스텀 예외를 정의하세요.
  * `SmsRateLimitException` (429 Too Many Requests)
  * `SmsVerificationExpiredException` (400 Bad Request)
  * `SmsVerificationMismatchException` (400 Bad Request)