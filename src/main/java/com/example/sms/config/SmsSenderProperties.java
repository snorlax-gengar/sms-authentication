package com.example.sms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "sms.sender")
public class SmsSenderProperties {

    private Provider provider = Provider.DEV;
    private String fromNumber;
    private int timeoutSeconds = 3;
    private Solapi solapi = new Solapi();

    public enum Provider {
        DEV,
        MOCK,
        SOLAPI
    }

    @Getter
    @Setter
    public static class Solapi {
        private String apiKey;
        private String apiSecret;
        private String baseUrl = "https://api.solapi.com";
    }
}
