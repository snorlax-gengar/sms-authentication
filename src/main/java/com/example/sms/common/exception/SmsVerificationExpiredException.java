package com.example.sms.common.exception;

public class SmsVerificationExpiredException extends SmsException {

    public SmsVerificationExpiredException() {
        super("SMS_VERIFICATION_EXPIRED", "인증번호가 만료되었거나 존재하지 않습니다.");
    }
}
