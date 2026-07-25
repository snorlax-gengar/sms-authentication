package com.example.sms.common.exception;

public class SmsRateLimitException extends SmsException {

    public SmsRateLimitException(String message) {
        super("SMS_RATE_LIMIT_EXCEEDED", message);
    }
}
