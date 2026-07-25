package com.example.sms.domain.auth.sender;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "sms.sender.provider", havingValue = "mock")
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String code) {
        log.info("Mock SMS sent to phone ending in ****{} (code not logged in production)", phoneNumber.substring(7));
    }
}
