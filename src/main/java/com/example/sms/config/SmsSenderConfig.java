package com.example.sms.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(SmsSenderProperties.class)
public class SmsSenderConfig {

    @Bean
    @ConditionalOnProperty(name = "sms.sender.provider", havingValue = "solapi")
    public RestClient solapiRestClient(SmsSenderProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(properties.getSolapi().getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
