package com.example.sms.domain.auth.sender.solapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Solapi 인증 헤더 생성기 테스트.
 * 서명이 규격과 조금이라도 어긋나면 실제 발송이 전량 실패하는 지점이므로,
 * 헤더 형식과 HMAC 서명값을 독립적으로 재계산해 검증한다.
 */
@DisplayName("SolapiAuthHeaderGenerator 유닛 테스트")
class SolapiAuthHeaderGeneratorTest {

    private static final String API_KEY = "TEST_API_KEY";
    private static final String API_SECRET = "TEST_API_SECRET";

    // HMAC-SHA256 apiKey=..., date=..., salt=..., signature=...
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^HMAC-SHA256 apiKey=(?<apiKey>[^,]+), date=(?<date>[^,]+), "
                    + "salt=(?<salt>[^,]+), signature=(?<signature>[0-9a-f]{64})$");

    @Test
    @DisplayName("Solapi 규격에 맞는 Authorization 헤더 형식을 생성한다")
    void createsHeaderInExpectedFormat() {
        // Given & When
        String header = SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, API_SECRET);

        // Then
        Matcher matcher = HEADER_PATTERN.matcher(header);
        assertThat(matcher.matches())
                .as("헤더 형식: %s", header)
                .isTrue();
        assertThat(matcher.group("apiKey")).isEqualTo(API_KEY);
        assertThat(matcher.group("salt")).hasSize(32);   // UUID에서 하이픈 제거
    }

    @Test
    @DisplayName("date는 ISO-8601 형식이며 파싱 가능한 시각이다")
    void dateIsIso8601() {
        // Given & When
        String header = SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, API_SECRET);
        Matcher matcher = HEADER_PATTERN.matcher(header);

        // Then
        assertThat(matcher.matches()).isTrue();
        Instant parsed = Instant.parse(matcher.group("date"));
        assertThat(parsed).isBefore(Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName("signature는 (date + salt)에 대한 HMAC-SHA256 hex 값과 일치한다")
    void signatureMatchesIndependentlyComputedHmac() throws Exception {
        // Given
        String header = SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, API_SECRET);
        Matcher matcher = HEADER_PATTERN.matcher(header);
        assertThat(matcher.matches()).isTrue();

        // When: 구현과 무관하게 직접 서명을 재계산한다
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(
                (matcher.group("date") + matcher.group("salt")).getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(hash);

        // Then
        assertThat(matcher.group("signature")).isEqualTo(expected);
    }

    @Test
    @DisplayName("호출할 때마다 salt와 signature가 달라진다 — 재전송 공격 방지")
    void generatesUniqueSaltPerCall() {
        // Given & When
        String first = SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, API_SECRET);
        String second = SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, API_SECRET);

        // Then
        assertThat(first).isNotEqualTo(second);

        Matcher m1 = HEADER_PATTERN.matcher(first);
        Matcher m2 = HEADER_PATTERN.matcher(second);
        assertThat(m1.matches()).isTrue();
        assertThat(m2.matches()).isTrue();
        assertThat(m1.group("salt")).isNotEqualTo(m2.group("salt"));
        assertThat(m1.group("signature")).isNotEqualTo(m2.group("signature"));
    }

    @Test
    @DisplayName("apiSecret이 다르면 서로 다른 서명이 생성된다")
    void differentSecretProducesDifferentSignature() throws Exception {
        // Given: 동일한 date/salt에 대해 두 개의 secret으로 서명
        String header = SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, API_SECRET);
        Matcher matcher = HEADER_PATTERN.matcher(header);
        assertThat(matcher.matches()).isTrue();
        String payload = matcher.group("date") + matcher.group("salt");

        // When
        String otherSignature = hmacHex("ANOTHER_SECRET", payload);

        // Then
        assertThat(matcher.group("signature")).isNotEqualTo(otherSignature);
    }

    @Test
    @DisplayName("apiSecret이 null이면 IllegalStateException으로 감싸서 던진다")
    void wrapsFailureWhenSecretIsNull() {
        // When & Then: 내부 예외를 삼키지 않고 명확한 예외로 변환한다
        assertThatThrownBy(() -> SolapiAuthHeaderGenerator.createAuthorizationHeader(API_KEY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to generate Solapi authorization header");
    }

    private String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
