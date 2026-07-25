package com.example.sms.domain.auth.controller;

import com.example.sms.common.exception.SmsRateLimitException;
import com.example.sms.common.exception.SmsSendFailedException;
import com.example.sms.common.exception.SmsVerificationAttemptExceededException;
import com.example.sms.common.exception.SmsVerificationExpiredException;
import com.example.sms.common.exception.SmsVerificationMismatchException;
import com.example.sms.domain.auth.service.SmsAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SmsAuthController 슬라이스 테스트.
 * Service는 Mocking하고 API 엔드포인트, 입력값 검증, HTTP 상태 코드, 응답 형식을 검증한다.
 */
@WebMvcTest(SmsAuthController.class)
@DisplayName("SmsAuthController 슬라이스 테스트")
class SmsAuthControllerTest {

    private static final String PHONE_NUMBER = "01012345678";
    private static final String CODE = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SmsAuthService smsAuthService;

    private String toJson(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Nested
    @DisplayName("POST /api/v1/sms/request: 인증번호 발송 요청")
    class RequestVerification {

        private static final String URL = "/api/v1/sms/request";

        @Test
        @DisplayName("정상 요청 시 200 OK와 성공 메시지를 반환한다")
        void success() throws Exception {
            // Given
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("인증번호가 발송되었습니다."))
                    .andExpect(jsonPath("$.error").doesNotExist());

            verify(smsAuthService).requestVerification(PHONE_NUMBER);
        }

        @Test
        @DisplayName("잘못된 전화번호 형식이면 400과 INVALID_REQUEST를 반환하고 Service를 호출하지 않는다")
        void failWhenPhoneNumberFormatInvalid() throws Exception {
            // Given: 010으로 시작하지 않는 번호
            String body = toJson(Map.of("phoneNumber", "01112345678"));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            verify(smsAuthService, never()).requestVerification(anyString());
        }

        @Test
        @DisplayName("자릿수가 부족한 전화번호면 400을 반환한다")
        void failWhenPhoneNumberTooShort() throws Exception {
            // Given: 10자리 번호
            String body = toJson(Map.of("phoneNumber", "0101234567"));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            verify(smsAuthService, never()).requestVerification(anyString());
        }

        @Test
        @DisplayName("전화번호가 빈 값이면 400을 반환한다")
        void failWhenPhoneNumberBlank() throws Exception {
            // Given
            String body = toJson(Map.of("phoneNumber", ""));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            verify(smsAuthService, never()).requestVerification(anyString());
        }

        @Test
        @DisplayName("전화번호 필드가 아예 없으면 400을 반환한다")
        void failWhenPhoneNumberMissing() throws Exception {
            // Given
            String body = "{}";

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());

            verify(smsAuthService, never()).requestVerification(anyString());
        }

        @Test
        @DisplayName("Rate Limit 초과 시 429와 SMS_RATE_LIMIT_EXCEEDED를 반환한다")
        void failWhenRateLimitExceeded() throws Exception {
            // Given
            willThrow(new SmsRateLimitException("1분 내 최대 요청 횟수(3회)를 초과했습니다."))
                    .given(smsAuthService).requestVerification(PHONE_NUMBER);
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SMS_RATE_LIMIT_EXCEEDED"));
        }

        @Test
        @DisplayName("SMS Gateway 발송 실패 시 502와 SMS_SEND_FAILED를 반환한다")
        void failWhenSmsGatewayFails() throws Exception {
            // Given
            willThrow(new SmsSendFailedException("SMS 발송에 실패했습니다."))
                    .given(smsAuthService).requestVerification(PHONE_NUMBER);
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SMS_SEND_FAILED"));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/sms/verify: 인증번호 검증")
    class Verify {

        private static final String URL = "/api/v1/sms/verify";

        @Test
        @DisplayName("정상 요청 시 200 OK와 성공 메시지를 반환한다")
        void success() throws Exception {
            // Given
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", CODE));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("인증에 성공했습니다."));

            verify(smsAuthService).verify(PHONE_NUMBER, CODE);
        }

        @Test
        @DisplayName("인증번호가 만료되었으면 400과 SMS_VERIFICATION_EXPIRED를 반환한다")
        void failWhenVerificationExpired() throws Exception {
            // Given
            willThrow(new SmsVerificationExpiredException())
                    .given(smsAuthService).verify(PHONE_NUMBER, CODE);
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", CODE));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_EXPIRED"));
        }

        @Test
        @DisplayName("인증번호가 일치하지 않으면 400과 SMS_VERIFICATION_MISMATCH를 반환한다")
        void failWhenVerificationMismatch() throws Exception {
            // Given
            willThrow(new SmsVerificationMismatchException())
                    .given(smsAuthService).verify(PHONE_NUMBER, CODE);
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", CODE));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_MISMATCH"));
        }

        @Test
        @DisplayName("인증 시도 횟수를 초과하면 429와 SMS_VERIFICATION_ATTEMPT_EXCEEDED를 반환한다")
        void failWhenAttemptExceeded() throws Exception {
            // Given
            willThrow(new SmsVerificationAttemptExceededException(5))
                    .given(smsAuthService).verify(PHONE_NUMBER, CODE);
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", CODE));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_ATTEMPT_EXCEEDED"));
        }

        @Test
        @DisplayName("인증번호가 6자리 숫자가 아니면 400을 반환하고 Service를 호출하지 않는다")
        void failWhenCodeFormatInvalid() throws Exception {
            // Given: 5자리 코드
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", "12345"));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            verify(smsAuthService, never()).verify(anyString(), anyString());
        }

        @Test
        @DisplayName("인증번호에 문자가 포함되면 400을 반환한다")
        void failWhenCodeContainsNonDigit() throws Exception {
            // Given
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", "12a456"));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            verify(smsAuthService, never()).verify(anyString(), anyString());
        }

        @Test
        @DisplayName("인증번호가 빈 값이면 400을 반환한다")
        void failWhenCodeBlank() throws Exception {
            // Given
            String body = toJson(Map.of("phoneNumber", PHONE_NUMBER, "code", ""));

            // When & Then
            mockMvc.perform(post(URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

            verify(smsAuthService, never()).verify(anyString(), anyString());
        }
    }
}
