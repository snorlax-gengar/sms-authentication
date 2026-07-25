package com.example.sms.common.exception;

import lombok.Getter;

@Getter
public class SmsException extends RuntimeException {

    private final String errorCode;

    public SmsException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
