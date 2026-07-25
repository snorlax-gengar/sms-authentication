package com.example.sms.domain.auth.sender;

public interface SmsSender {

    void send(String phoneNumber, String code);
}
