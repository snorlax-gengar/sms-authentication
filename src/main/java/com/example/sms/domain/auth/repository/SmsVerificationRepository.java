package com.example.sms.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SmsVerificationRepository {

    private static final String KEY_PREFIX = "sms:verify:";
    private static final String ATTEMPT_KEY_PREFIX = "sms:verify:attempt:";

    private final StringRedisTemplate redisTemplate;

    @Value("${sms.verification.ttl-seconds:180}")
    private long ttlSeconds;

    public void save(String phoneNumber, String code) {
        redisTemplate.opsForValue().set(
                buildKey(phoneNumber),
                code,
                Duration.ofSeconds(ttlSeconds)
        );
    }

    public Optional<String> findByPhoneNumber(String phoneNumber) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(buildKey(phoneNumber)));
    }

    public void delete(String phoneNumber) {
        redisTemplate.delete(buildKey(phoneNumber));
    }

    /**
     * 인증번호 검증 실패 횟수를 1 증가시키고 증가 후의 값을 반환한다.
     * INCR의 반환값을 그대로 사용하므로 동시 요청 상황에서도 원자적으로 카운트된다.
     * 카운터 TTL은 인증번호와 동일하게 설정하여 인증번호 만료와 함께 자동 소멸시킨다.
     */
    public long incrementAttemptCount(String phoneNumber) {
        String key = buildAttemptKey(phoneNumber);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        }
        return count != null ? count : 0L;
    }

    public void deleteAttemptCount(String phoneNumber) {
        redisTemplate.delete(buildAttemptKey(phoneNumber));
    }

    private String buildKey(String phoneNumber) {
        return KEY_PREFIX + phoneNumber;
    }

    private String buildAttemptKey(String phoneNumber) {
        return ATTEMPT_KEY_PREFIX + phoneNumber;
    }
}
