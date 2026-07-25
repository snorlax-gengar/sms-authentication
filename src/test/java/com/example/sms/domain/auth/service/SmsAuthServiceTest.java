package com.example.sms.domain.auth.service;

import com.example.sms.common.exception.SmsRateLimitException;
import com.example.sms.common.exception.SmsSendFailedException;
import com.example.sms.common.exception.SmsVerificationAttemptExceededException;
import com.example.sms.common.exception.SmsVerificationExpiredException;
import com.example.sms.common.exception.SmsVerificationMismatchException;
import com.example.sms.domain.auth.repository.SendStatus;
import com.example.sms.domain.auth.repository.SmsRateLimitRepository;
import com.example.sms.domain.auth.repository.SmsSendLog;
import com.example.sms.domain.auth.repository.SmsSendLogRepository;
import com.example.sms.domain.auth.repository.SmsVerificationRepository;
import com.example.sms.domain.auth.sender.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SmsAuthService 순수 유닛 테스트.
 * 외부 의존성(Redis, RDB, SMS Gateway)은 모두 Mocking 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SmsAuthService 유닛 테스트")
class SmsAuthServiceTest {

    private static final String PHONE_NUMBER = "01012345678";
    private static final int MINUTE_MAX = 3;
    private static final int DAILY_MAX = 5;
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private SmsVerificationRepository verificationRepository;

    @Mock
    private SmsRateLimitRepository rateLimitRepository;

    @Mock
    private SmsSendLogRepository sendLogRepository;

    @Mock
    private SmsSender smsSender;

    @InjectMocks
    private SmsAuthService smsAuthService;

    @Captor
    private ArgumentCaptor<SmsSendLog> sendLogCaptor;

    @BeforeEach
    void setUp() {
        // @Value 필드는 순수 유닛 테스트에서 주입되지 않으므로 직접 세팅
        ReflectionTestUtils.setField(smsAuthService, "minuteMax", MINUTE_MAX);
        ReflectionTestUtils.setField(smsAuthService, "dailyMax", DAILY_MAX);
        ReflectionTestUtils.setField(smsAuthService, "maxAttempts", MAX_ATTEMPTS);
    }

    @Nested
    @DisplayName("requestVerification: 인증번호 발송 요청")
    class RequestVerification {

        @Test
        @DisplayName("정상 요청 시 6자리 인증번호를 저장하고 SMS를 발송하며 SENT 로그를 남긴다")
        void success() {
            // Given
            given(rateLimitRepository.incrementMinuteCount(PHONE_NUMBER)).willReturn(1L);
            given(rateLimitRepository.incrementDailyCount(PHONE_NUMBER)).willReturn(1L);

            // When
            smsAuthService.requestVerification(PHONE_NUMBER);

            // Then: 저장된 코드와 발송된 코드가 동일한 6자리 숫자여야 한다
            ArgumentCaptor<String> savedCodeCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
            verify(verificationRepository).save(eq(PHONE_NUMBER), savedCodeCaptor.capture());
            verify(smsSender).send(eq(PHONE_NUMBER), sentCodeCaptor.capture());

            assertThat(savedCodeCaptor.getValue()).matches("^\\d{6}$");
            assertThat(sentCodeCaptor.getValue()).isEqualTo(savedCodeCaptor.getValue());

            // Then: 새 인증번호 발급 시 이전 시도 횟수는 초기화된다
            verify(verificationRepository).deleteAttemptCount(PHONE_NUMBER);

            // Then: SENT 발송 이력 기록
            verify(sendLogRepository).save(sendLogCaptor.capture());
            SmsSendLog savedLog = sendLogCaptor.getValue();
            assertThat(savedLog.getPhoneNumber()).isEqualTo(PHONE_NUMBER);
            assertThat(savedLog.getStatus()).isEqualTo(SendStatus.SENT);
            assertThat(savedLog.getErrorMsg()).isNull();
        }

        @Test
        @DisplayName("분당 제한과 정확히 같은 횟수(3회째)까지는 정상 발송된다 — 경계값 검증")
        void successAtMinuteBoundary() {
            // Given: INCR 결과가 제한치와 동일 (3회째 요청)
            given(rateLimitRepository.incrementMinuteCount(PHONE_NUMBER)).willReturn((long) MINUTE_MAX);
            given(rateLimitRepository.incrementDailyCount(PHONE_NUMBER)).willReturn((long) DAILY_MAX);

            // When
            smsAuthService.requestVerification(PHONE_NUMBER);

            // Then
            verify(smsSender).send(eq(PHONE_NUMBER), anyString());
        }

        @Test
        @DisplayName("분당 요청 제한(3회) 초과 시 SmsRateLimitException이 발생하고 발송이 차단된다")
        void failWhenMinuteRateLimitExceeded() {
            // Given: 4회째 요청 (INCR 결과가 제한치 초과)
            given(rateLimitRepository.incrementMinuteCount(PHONE_NUMBER)).willReturn((long) MINUTE_MAX + 1);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.requestVerification(PHONE_NUMBER))
                    .isInstanceOf(SmsRateLimitException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SMS_RATE_LIMIT_EXCEEDED");

            // Then: 분당 제한에 걸리면 일일 카운터는 증가시키지 않는다
            verify(rateLimitRepository, never()).incrementDailyCount(anyString());
            verifyNoInteractions(verificationRepository, smsSender, sendLogRepository);
        }

        @Test
        @DisplayName("일일 요청 제한(5회) 초과 시 SmsRateLimitException이 발생하고 발송이 차단된다")
        void failWhenDailyRateLimitExceeded() {
            // Given: 분당 제한은 통과, 일일 제한 초과
            given(rateLimitRepository.incrementMinuteCount(PHONE_NUMBER)).willReturn(1L);
            given(rateLimitRepository.incrementDailyCount(PHONE_NUMBER)).willReturn((long) DAILY_MAX + 1);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.requestVerification(PHONE_NUMBER))
                    .isInstanceOf(SmsRateLimitException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SMS_RATE_LIMIT_EXCEEDED");

            verifyNoInteractions(verificationRepository, smsSender, sendLogRepository);
        }

        @Test
        @DisplayName("SMS 발송 실패 시 FAILED 로그를 기록하고 예외를 다시 던진다")
        void failWhenSmsSendFails() {
            // Given
            given(rateLimitRepository.incrementMinuteCount(PHONE_NUMBER)).willReturn(1L);
            given(rateLimitRepository.incrementDailyCount(PHONE_NUMBER)).willReturn(1L);
            willThrow(new SmsSendFailedException("Gateway timeout"))
                    .given(smsSender).send(eq(PHONE_NUMBER), anyString());

            // When & Then
            assertThatThrownBy(() -> smsAuthService.requestVerification(PHONE_NUMBER))
                    .isInstanceOf(SmsSendFailedException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SMS_SEND_FAILED");

            // Then: FAILED 발송 이력이 에러 메시지와 함께 기록된다
            // (@Transactional을 제거했으므로 예외 전파와 무관하게 이 저장은 유지된다)
            verify(sendLogRepository).save(sendLogCaptor.capture());
            SmsSendLog failedLog = sendLogCaptor.getValue();
            assertThat(failedLog.getStatus()).isEqualTo(SendStatus.FAILED);
            assertThat(failedLog.getErrorMsg()).isEqualTo("Gateway timeout");
        }
    }

    @Nested
    @DisplayName("verify: 인증번호 검증")
    class Verify {

        private static final String CODE = "123456";

        @Test
        @DisplayName("저장된 인증번호와 일치하면 검증에 성공하고 재사용 방지를 위해 키를 삭제한다")
        void success() {
            // Given
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.of(CODE));

            // When
            smsAuthService.verify(PHONE_NUMBER, CODE);

            // Then: 재사용 방지 — 인증번호와 시도 횟수 카운터를 모두 삭제
            verify(verificationRepository).delete(PHONE_NUMBER);
            verify(verificationRepository).deleteAttemptCount(PHONE_NUMBER);
        }

        @Test
        @DisplayName("인증번호가 만료되었거나 존재하지 않으면 SmsVerificationExpiredException이 발생한다")
        void failWhenExpiredOrNotFound() {
            // Given: TTL 만료로 Redis에 값이 없는 상태
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> smsAuthService.verify(PHONE_NUMBER, CODE))
                    .isInstanceOf(SmsVerificationExpiredException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SMS_VERIFICATION_EXPIRED");

            verify(verificationRepository, never()).delete(anyString());
            verify(verificationRepository, never()).incrementAttemptCount(anyString());
        }

        @Test
        @DisplayName("인증번호가 일치하지 않으면 시도 횟수를 증가시키고 불일치 예외가 발생하며 키는 유지된다")
        void failWhenCodeMismatch() {
            // Given
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.of(CODE));
            given(verificationRepository.incrementAttemptCount(PHONE_NUMBER)).willReturn(1L);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.verify(PHONE_NUMBER, "654321"))
                    .isInstanceOf(SmsVerificationMismatchException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SMS_VERIFICATION_MISMATCH");

            // Then: 남은 시도 기회가 있으므로 인증번호는 유지한다
            verify(verificationRepository).incrementAttemptCount(PHONE_NUMBER);
            verify(verificationRepository, never()).delete(anyString());
        }

        @Test
        @DisplayName("시도 횟수 제한 직전(4회째 실패)까지는 인증번호가 유지된다 — 경계값 검증")
        void keepsCodeJustBelowAttemptLimit() {
            // Given: 이번 실패로 누적 4회 (제한 5회 미만)
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.of(CODE));
            given(verificationRepository.incrementAttemptCount(PHONE_NUMBER))
                    .willReturn((long) MAX_ATTEMPTS - 1);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.verify(PHONE_NUMBER, "000000"))
                    .isInstanceOf(SmsVerificationMismatchException.class);

            verify(verificationRepository, never()).delete(anyString());
        }

        @Test
        @DisplayName("시도 횟수(5회)를 초과하면 인증번호를 폐기하고 SmsVerificationAttemptExceededException이 발생한다")
        void discardsCodeWhenAttemptLimitExceeded() {
            // Given: 이번 실패로 누적 5회 도달 (무차별 대입 차단 시점)
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.of(CODE));
            given(verificationRepository.incrementAttemptCount(PHONE_NUMBER))
                    .willReturn((long) MAX_ATTEMPTS);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.verify(PHONE_NUMBER, "000000"))
                    .isInstanceOf(SmsVerificationAttemptExceededException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SMS_VERIFICATION_ATTEMPT_EXCEEDED");

            // Then: 인증번호와 카운터를 모두 폐기 — 재발송을 강제한다
            verify(verificationRepository).delete(PHONE_NUMBER);
            verify(verificationRepository).deleteAttemptCount(PHONE_NUMBER);
        }

        @Test
        @DisplayName("빈 문자열 인증번호로 검증 시도 시 불일치 예외가 발생한다")
        void failWhenCodeIsEmpty() {
            // Given
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.of(CODE));
            given(verificationRepository.incrementAttemptCount(PHONE_NUMBER)).willReturn(1L);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.verify(PHONE_NUMBER, ""))
                    .isInstanceOf(SmsVerificationMismatchException.class);

            verify(verificationRepository, never()).delete(anyString());
        }

        @Test
        @DisplayName("null 인증번호로 검증 시도해도 NPE 없이 불일치 예외가 발생한다")
        void failWhenCodeIsNull() {
            // Given
            given(verificationRepository.findByPhoneNumber(PHONE_NUMBER))
                    .willReturn(Optional.of(CODE));
            given(verificationRepository.incrementAttemptCount(PHONE_NUMBER)).willReturn(1L);

            // When & Then
            assertThatThrownBy(() -> smsAuthService.verify(PHONE_NUMBER, null))
                    .isInstanceOf(SmsVerificationMismatchException.class);
        }
    }
}
