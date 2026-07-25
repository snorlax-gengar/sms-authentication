package com.example.sms.common.exception;

/**
 * 인증번호 검증 시도 횟수를 초과한 경우 발생한다.
 * 6자리 인증번호에 대한 무차별 대입(brute-force) 공격을 차단하기 위한 방어 장치로,
 * 이 예외가 발생하는 시점에 해당 인증번호는 폐기되어 재발송이 필요하다.
 */
public class SmsVerificationAttemptExceededException extends SmsException {

    public SmsVerificationAttemptExceededException(int maxAttempts) {
        super("SMS_VERIFICATION_ATTEMPT_EXCEEDED",
                "인증 시도 횟수(" + maxAttempts + "회)를 초과했습니다. 인증번호를 다시 요청해주세요.");
    }
}
