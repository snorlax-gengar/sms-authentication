package com.example.sms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smsAuthOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SMS Auth API")
                        .description("SMS 인증번호 발송 및 검증 API 명세서")
                        .version("v1")
                        .contact(new Contact().name("sms-auth")));
    }
}
