package com.example.sms.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
@RequiredArgsConstructor
public class SmsRateLimitRepository {

    private static final String MINUTE_KEY_PREFIX = "sms:rate:minute:";
    private static final String DAILY_KEY_PREFIX = "sms:rate:daily:";

    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration DAILY_WINDOW = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;

    /**
     * 분당 요청 카운터를 1 증가시키고 증가 후의 값을 반환한다.
     * 호출자는 이 반환값으로 제한 초과 여부를 판정한다 — 조회와 증가를 분리하면
     * 동시 요청 시 제한을 넘겨 통과하는 경쟁 조건이 생기기 때문이다.
     */
    public long incrementMinuteCount(String phoneNumber) {
        return incrementWithTtl(MINUTE_KEY_PREFIX + phoneNumber, MINUTE_WINDOW);
    }

    /**
     * 일일 요청 카운터를 1 증가시키고 증가 후의 값을 반환한다.
     */
    public long incrementDailyCount(String phoneNumber) {
        return incrementWithTtl(DAILY_KEY_PREFIX + phoneNumber, DAILY_WINDOW);
    }

    private long incrementWithTtl(String key, Duration ttl) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return count != null ? count : 0L;
    }
}
