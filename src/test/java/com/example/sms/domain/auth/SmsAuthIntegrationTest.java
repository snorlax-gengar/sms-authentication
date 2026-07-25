package com.example.sms.domain.auth;

import com.example.sms.domain.auth.repository.SendStatus;
import com.example.sms.domain.auth.repository.SmsSendLog;
import com.example.sms.domain.auth.repository.SmsSendLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 PostgreSQL·Redis 컨테이너를 띄워 전체 인증 플로우를 검증하는 통합 테스트.
 * <p>
 * 의도적으로 {@code prod} 프로필로 실행한다. prod는 {@code ddl-auto: validate} +
 * {@code sql.init.mode: always} 이므로, 이 테스트가 통과한다는 것은
 * <b>schema.sql의 DDL과 SmsSendLog 엔티티가 일치함</b>을 함께 보증한다는 뜻이다.
 * (불일치 시 애플리케이션 컨텍스트 로딩 단계에서 실패한다.)
 * <p>
 * 실행에 Docker가 필요하다. Docker가 없는 환경에서는
 * {@code ./gradlew test -PexcludeIntegration} 으로 제외할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers
@Tag("integration")
@DisplayName("SMS 인증 통합 테스트 (Testcontainers)")
class SmsAuthIntegrationTest {

    private static final String PHONE_NUMBER = "01012345678";
    private static final String VERIFY_KEY = "sms:verify:" + PHONE_NUMBER;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /**
     * application-prod.yml이 참조하는 환경 변수 자리에 컨테이너 접속 정보를 주입한다.
     * 운영 설정 파일을 그대로 통과시키므로 prod 설정 자체의 유효성도 함께 검증된다.
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", postgres::getHost);
        registry.add("DB_PORT", () -> postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        registry.add("DB_NAME", postgres::getDatabaseName);
        registry.add("DB_USERNAME", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        // 실제 문자 발송 없이 성공만 기록하는 구현체
        registry.add("sms.sender.provider", () -> "mock");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SmsSendLogRepository sendLogRepository;

    @BeforeEach
    void cleanUp() {
        // 컨테이너는 클래스 단위로 공유되므로 테스트 간 상태(Rate Limit 카운터 포함)를 초기화한다.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        sendLogRepository.deleteAll();
    }

    private String requestBody(Map<String, String> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private void requestVerification() throws Exception {
        mockMvc.perform(post("/api/v1/sms/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER))))
                .andExpect(status().isOk());
    }

    @Nested
    @DisplayName("전체 인증 플로우")
    class FullFlow {

        @Test
        @DisplayName("발송 요청 후 실제 Redis에 저장된 인증번호로 검증하면 성공하고 키가 삭제된다")
        void requestThenVerify() throws Exception {
            // Given: 인증번호 발송 요청
            requestVerification();

            // Then: Redis에 6자리 인증번호가 TTL과 함께 저장된다
            String storedCode = redisTemplate.opsForValue().get(VERIFY_KEY);
            assertThat(storedCode).isNotNull().matches("^\\d{6}$");
            assertThat(redisTemplate.getExpire(VERIFY_KEY)).isPositive();

            // Then: RDB에 SENT 발송 이력이 실제로 커밋된다
            List<SmsSendLog> logs = sendLogRepository.findAll();
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getStatus()).isEqualTo(SendStatus.SENT);
            assertThat(logs.get(0).getCreatedAt()).isNotNull();   // JPA Auditing 동작 확인

            // When: 저장된 인증번호로 검증
            mockMvc.perform(post("/api/v1/sms/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER, "code", storedCode))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Then: 재사용 방지를 위해 Redis 키가 삭제된다
            assertThat(redisTemplate.opsForValue().get(VERIFY_KEY)).isNull();
        }

        @Test
        @DisplayName("인증에 성공한 번호는 같은 인증번호로 재검증할 수 없다")
        void cannotReuseVerifiedCode() throws Exception {
            // Given: 발송 후 인증 성공
            requestVerification();
            String storedCode = redisTemplate.opsForValue().get(VERIFY_KEY);
            mockMvc.perform(post("/api/v1/sms/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER, "code", storedCode))))
                    .andExpect(status().isOk());

            // When & Then: 동일한 인증번호로 재시도하면 만료 처리된다
            mockMvc.perform(post("/api/v1/sms/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER, "code", storedCode))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_EXPIRED"));
        }
    }

    @Nested
    @DisplayName("Rate Limit (실제 Redis 카운터)")
    class RateLimit {

        @Test
        @DisplayName("분당 제한(3회)까지는 통과하고 4회째 요청부터 429를 반환한다")
        void blocksAfterMinuteLimit() throws Exception {
            // Given & When: 제한치까지 요청
            for (int i = 0; i < 3; i++) {
                requestVerification();
            }

            // Then: 4회째는 차단된다
            mockMvc.perform(post("/api/v1/sms/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("SMS_RATE_LIMIT_EXCEEDED"));

            // Then: 차단된 요청은 발송 이력을 남기지 않는다
            assertThat(sendLogRepository.findAll()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("무차별 대입 방어 (실제 Redis 카운터)")
    class BruteForceProtection {

        @Test
        @DisplayName("5회 연속 오답 시 인증번호가 폐기되어 정답으로도 검증할 수 없다")
        void discardsCodeAfterMaxAttempts() throws Exception {
            // Given: 인증번호 발송
            requestVerification();
            String storedCode = redisTemplate.opsForValue().get(VERIFY_KEY);
            String wrongCode = storedCode.equals("000000") ? "111111" : "000000";

            // When: 4회까지는 불일치 응답
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post("/api/v1/sms/verify")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER, "code", wrongCode))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_MISMATCH"));
            }

            // Then: 5회째에 인증번호가 폐기된다
            mockMvc.perform(post("/api/v1/sms/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER, "code", wrongCode))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_ATTEMPT_EXCEEDED"));

            assertThat(redisTemplate.opsForValue().get(VERIFY_KEY)).isNull();

            // Then: 원래 정답이었던 인증번호도 더 이상 통하지 않는다
            mockMvc.perform(post("/api/v1/sms/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(Map.of("phoneNumber", PHONE_NUMBER, "code", storedCode))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SMS_VERIFICATION_EXPIRED"));
        }
    }
}
