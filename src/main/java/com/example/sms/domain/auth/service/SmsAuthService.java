package com.example.sms.domain.auth.service;

import com.example.sms.common.exception.SmsRateLimitException;
import com.example.sms.common.exception.SmsVerificationAttemptExceededException;
import com.example.sms.common.exception.SmsVerificationExpiredException;
import com.example.sms.common.exception.SmsVerificationMismatchException;
import com.example.sms.domain.auth.repository.SmsRateLimitRepository;
import com.example.sms.domain.auth.repository.SmsSendLog;
import com.example.sms.domain.auth.repository.SmsSendLogRepository;
import com.example.sms.domain.auth.repository.SmsVerificationRepository;
import com.example.sms.domain.auth.sender.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsVerificationRepository verificationRepository;
    private final SmsRateLimitRepository rateLimitRepository;
    private final SmsSendLogRepository sendLogRepository;
    private final SmsSender smsSender;

    @Value("${sms.rate-limit.minute-max:3}")
    private int minuteMax;

    @Value("${sms.rate-limit.daily-max:5}")
    private int dailyMax;

    @Value("${sms.verification.max-attempts:5}")
    private int maxAttempts;

    /**
     * 인증번호를 생성해 Redis에 저장하고 SMS로 발송한다.
     * <p>
     * 의도적으로 {@code @Transactional}을 사용하지 않는다. 이 메서드는 Redis 쓰기와 외부 HTTP 호출을
     * 포함하는데, 트랜잭션으로 감싸면 (1) 외부 API 응답을 기다리는 동안 DB 커넥션을 점유하고,
     * (2) 발송 실패 시 예외 전파와 함께 FAILED 이력까지 롤백되어 장애 분석 데이터가 유실된다.
     * 발송 이력 저장은 단건 INSERT이므로 {@code JpaRepository.save()} 자체 트랜잭션으로 충분하다.
     */
    public void requestVerification(String phoneNumber) {
        validateRateLimit(phoneNumber);

        String code = generateVerificationCode();
        verificationRepository.save(phoneNumber, code);
        // 인증번호를 새로 발급했으므로 이전 인증번호에 대한 실패 횟수는 초기화한다.
        verificationRepository.deleteAttemptCount(phoneNumber);

        try {
            smsSender.send(phoneNumber, code);
            sendLogRepository.save(SmsSendLog.success(phoneNumber));
            log.info("Verification code requested for {}", mask(phoneNumber));
        } catch (Exception ex) {
            sendLogRepository.save(SmsSendLog.failure(phoneNumber, ex.getMessage()));
            log.error("Failed to send SMS to {}", mask(phoneNumber), ex);
            throw ex;
        }
    }

    /**
     * 인증번호를 검증한다. 성공 시 인증번호와 시도 횟수 카운터를 모두 삭제해 재사용을 방지한다.
     * 실패가 누적되어 최대 시도 횟수에 도달하면 인증번호 자체를 폐기하여 무차별 대입을 차단한다.
     */
    public void verify(String phoneNumber, String code) {
        String storedCode = verificationRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(SmsVerificationExpiredException::new);

        if (!matches(storedCode, code)) {
            long attempts = verificationRepository.incrementAttemptCount(phoneNumber);
            if (attempts >= maxAttempts) {
                verificationRepository.delete(phoneNumber);
                verificationRepository.deleteAttemptCount(phoneNumber);
                log.warn("Verification attempts exceeded for {} — code discarded", mask(phoneNumber));
                throw new SmsVerificationAttemptExceededException(maxAttempts);
            }
            log.warn("Verification mismatch for {} ({}/{} attempts)", mask(phoneNumber), attempts, maxAttempts);
            throw new SmsVerificationMismatchException();
        }

        verificationRepository.delete(phoneNumber);
        verificationRepository.deleteAttemptCount(phoneNumber);
        log.info("Verification succeeded for {}", mask(phoneNumber));
    }

    /**
     * Rate Limit을 검증한다.
     * <p>
     * 조회 후 증가시키는 check-then-act 방식은 동시 요청 시 제한을 넘겨 통과하는 경쟁 조건이 있으므로,
     * Redis {@code INCR}의 반환값으로 즉시 판정한다. 카운터를 먼저 올리기 때문에 판정 기준은
     * {@code > max}이다 (minuteMax=3이면 4번째 요청부터 차단).
     */
    private void validateRateLimit(String phoneNumber) {
        long minuteCount = rateLimitRepository.incrementMinuteCount(phoneNumber);
        if (minuteCount > minuteMax) {
            throw new SmsRateLimitException("1분 내 최대 요청 횟수(" + minuteMax + "회)를 초과했습니다.");
        }

        long dailyCount = rateLimitRepository.incrementDailyCount(phoneNumber);
        if (dailyCount > dailyMax) {
            throw new SmsRateLimitException("일일 최대 요청 횟수(" + dailyMax + "회)를 초과했습니다.");
        }
    }

    private String generateVerificationCode() {
        int code = RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    /**
     * 인증번호 비교. 입력값에 따라 비교 시간이 달라지지 않도록 상수 시간 비교를 사용한다.
     */
    private boolean matches(String storedCode, String inputCode) {
        if (inputCode == null) {
            return false;
        }
        return MessageDigest.isEqual(
                storedCode.getBytes(StandardCharsets.UTF_8),
                inputCode.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 로그에 전화번호 원본을 남기지 않기 위한 마스킹.
     * 길이 검사 없이 substring을 호출하면 형식이 어긋난 입력에서
     * StringIndexOutOfBoundsException이 발생하므로 방어적으로 처리한다.
     */
    private String mask(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        return "****" + phoneNumber.substring(phoneNumber.length() - 4);
    }
}
