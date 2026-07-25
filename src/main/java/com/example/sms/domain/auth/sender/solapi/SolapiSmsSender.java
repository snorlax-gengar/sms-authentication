package com.example.sms.domain.auth.sender.solapi;

import com.example.sms.common.exception.SmsSendFailedException;
import com.example.sms.config.SmsSenderProperties;
import com.example.sms.domain.auth.sender.SmsSender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sms.sender.provider", havingValue = "solapi")
public class SolapiSmsSender implements SmsSender {

    private static final String SEND_PATH = "/messages/v4/send";

    private final RestClient solapiRestClient;
    private final SmsSenderProperties properties;

    @PostConstruct
    void validateConfiguration() {
        SmsSenderProperties.Solapi solapi = properties.getSolapi();
        if (!StringUtils.hasText(solapi.getApiKey())) {
            throw new IllegalStateException("SOLAPI_API_KEY is required when sms.sender.provider=solapi");
        }
        if (!StringUtils.hasText(solapi.getApiSecret())) {
            throw new IllegalStateException("SOLAPI_API_SECRET is required when sms.sender.provider=solapi");
        }
        if (!StringUtils.hasText(properties.getFromNumber())) {
            throw new IllegalStateException("SMS_FROM_NUMBER is required when sms.sender.provider=solapi");
        }
    }

    @Override
    public void send(String phoneNumber, String code) {
        SmsSenderProperties.Solapi solapi = properties.getSolapi();
        String authorization = SolapiAuthHeaderGenerator.createAuthorizationHeader(
                solapi.getApiKey(),
                solapi.getApiSecret()
        );

        SolapiSendRequest request = new SolapiSendRequest(
                new SolapiSendRequest.SolapiMessage(
                        phoneNumber,
                        properties.getFromNumber(),
                        buildMessageText(code),
                        "SMS"
                )
        );

        try {
            solapiRestClient.post()
                    .uri(SEND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", authorization)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Solapi SMS sent to phone ending in ****{}", phoneNumber.substring(7));
        } catch (RestClientResponseException ex) {
            log.error("Solapi API error: status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new SmsSendFailedException("SMS 발송에 실패했습니다. 잠시 후 다시 시도해주세요.");
        } catch (Exception ex) {
            log.error("Solapi SMS send failed for phone ending in ****{}", phoneNumber.substring(7), ex);
            throw new SmsSendFailedException("SMS 발송 중 오류가 발생했습니다.");
        }
    }

    private String buildMessageText(String code) {
        return "[sms-auth] 인증번호는 [%s]입니다. 3분 내에 입력해주세요.".formatted(code);
    }
}
