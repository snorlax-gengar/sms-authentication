---
description: "유닛 테스트(Unit Test) 및 통합 테스트(Integration Test) 코드를 작성하거나 리팩토링할 때 적용합니다."
alwaysApply: false
---

## 1. 테스트 작성 원칙
* **Given-When-Then 패턴**: 모든 테스트 케이스는 Given(준비), When(실행), Then(검증) 구조를 명확히 주석으로 구분하여 작성하세요.
* **테스트 범위**: 
  * 비즈니스 로직(Service)은 외부 의존성(DB, SMS API)을 Mocking하여 순수 유닛 테스트로 진행합니다.
  * Controller는 API 엔드포인트와 HTTP 상태 코드를 검증하는 통합 테스트를 작성합니다.

## 2. 필수 검증 케이스 (Edge Cases)
테스트 작성 시 정상 흐름뿐만 아니라 다음 예외 상황을 반드시 포함하세요:
* 유효하지 않은 입력값 (빈 값, 잘못된 포맷)
* 만료된 토큰/인증번호로 접근하는 경우
* 일일 요청 제한(Rate Limit)을 초과한 경우