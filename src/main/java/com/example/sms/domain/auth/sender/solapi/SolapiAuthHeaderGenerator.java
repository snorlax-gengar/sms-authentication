package com.example.sms.domain.auth.sender.solapi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class SolapiAuthHeaderGenerator {

    private static final String ALGORITHM = "HmacSHA256";

    private SolapiAuthHeaderGenerator() {
    }

    public static String createAuthorizationHeader(String apiKey, String apiSecret) {
        try {
            String dateTime = Instant.now().toString();
            String salt = UUID.randomUUID().toString().replace("-", "");
            String signature = generateSignature(apiSecret, dateTime, salt);

            return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s"
                    .formatted(apiKey, dateTime, salt, signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Solapi authorization header", ex);
        }
    }

    private static String generateSignature(String apiSecret, String dateTime, String salt) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        byte[] hash = mac.doFinal((dateTime + salt).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
