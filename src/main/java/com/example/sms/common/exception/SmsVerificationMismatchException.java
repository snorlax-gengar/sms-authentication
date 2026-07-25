package com.example.sms.common.exception;

public class SmsVerificationMismatchException extends SmsException {

    public SmsVerificationMismatchException() {
        super("SMS_VERIFICATION_MISMATCH", "인증번호가 일치하지 않습니다.");
    }
}
