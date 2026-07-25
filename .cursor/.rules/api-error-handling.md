---
description: "RESTful API 설계, Request/Response DTO 정의, 통일된 에러 응답 구조가 필요할 때 적용합니다."
alwaysApply: false
---

## 1. API 응답 포맷 규격
모든 API 응답은 일관된 JSON 구조를 가져야 합니다. 성공과 실패 시의 구조를 통일하세요.

### 성공 응답 예시
```json
{
  "success": true,
  "data": { ... },
  "message": "요청이 성공적으로 처리되었습니다."
}
```

### 실패 응답 예시
```json
{
  "success": false,
  "error": {
    "code": "INVALID_PHONE_NUMBER",
    "message": "올바르지 않은 전화번호 형식입니다."
  }
}
```

## 2. 외부 연동(Third-party) 및 비동기 처리
SMS Gateway(알림톡, 외부 통신사 API) 등 외부 서비스와 통신하는 코드는 반드시 Timeout을 설정하고, 재시도(Retry) 로직이나 서킷 브레이커(Circuit Breaker) 패턴을 고려하여 작성하세요.

외부 API 호출부는 인터페이스로 추상화하여, 모의 테스트(Mock Test)가 가능하도록 구현하세요.