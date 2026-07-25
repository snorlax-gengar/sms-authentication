package com.example.sms.domain.auth.sender.solapi;

public record SolapiSendRequest(SolapiMessage message) {

    public record SolapiMessage(
            String to,
            String from,
            String text,
            String type
    ) {
    }
}
