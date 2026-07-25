package com.example.sms.common.exception;

public class SmsSendFailedException extends SmsException {

    public SmsSendFailedException(String message) {
        super("SMS_SEND_FAILED", message);
    }
}
