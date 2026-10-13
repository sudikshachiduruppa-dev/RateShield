package com.rateshield.authentication.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Standard HMAC-SHA256 JWT Token Provider.
 *
 * Implemented using pure Java cryptography (javax.crypto.Mac) and Jackson 3.x ObjectMapper
 * (tools.jackson.databind.ObjectMapper from Spring Boot 4.x starter) to guarantee 100% dependency compatibility.
 */
@Component
public class JwtTokenProvider {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String secretKey;
    private final long validityInSeconds;
    private final ObjectMapper objectMapper;

    public JwtTokenProvider(
            @Value("${rateshield.jwt.secret:}")
            String secretKey,
            @Value("${rateshield.jwt.expiration-seconds:86400}")
            long validityInSeconds
    ) {
        if (secretKey == null || secretKey.trim().isBlank()) {
            throw new IllegalStateException(
                    "JWT secret key is not configured. Set the RATESHIELD_JWT_SECRET or JWT_SECRET environment variable."
            );
        }
        if (secretKey.trim().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT secret key is too weak. It must be at least 256 bits (32 bytes) long."
            );
        }
        this.secretKey = secretKey.trim();
        this.validityInSeconds = validityInSeconds;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Generates a signed JWT with subject = username.
     */
    public String generateToken(String username) {
        try {
            long now = Instant.now().getEpochSecond();
            long exp = now + validityInSeconds;

            // 1. Header
            Map<String, Object> headerMap = Map.of(
                    "alg", "HS256",
                    "typ", "JWT"
            );
            String encodedHeader = base64UrlEncode(objectMapper.writeValueAsBytes(headerMap));

            // 2. Payload
            Map<String, Object> payloadMap = Map.of(
                    "sub", username,
                    "iat", now,
                    "exp", exp
            );
            String encodedPayload = base64UrlEncode(objectMapper.writeValueAsBytes(payloadMap));

            // 3. Signature
            String content = encodedHeader + "." + encodedPayload;
            String signature = sign(content, secretKey);

            return content + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate JWT token", e);
        }
    }

    /**
     * Validates the token signature and expiration.
     */
    public boolean validateToken(String token) {
        try {
            if (token == null || token.isBlank()) {
                return false;
            }

            token = token.trim();
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }

            String headerAndPayload = parts[0] + "." + parts[1];
            String expectedSignature = sign(headerAndPayload, secretKey);

            // Constant-time comparison to prevent timing attacks
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return false;
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);

            Object expObj = payload.get("exp");
            long exp;
            if (expObj instanceof Number expNumber) {
                exp = expNumber.longValue();
            } else if (expObj instanceof String expStr) {
                exp = Long.parseLong(expStr);
            } else {
                return false;
            }

            return Instant.now().getEpochSecond() < exp;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extracts username from validated token.
     */
    public String getUsernameFromToken(String token) {
        try {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Token cannot be null or blank");
            }
            token = token.trim();
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Malformed token");
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            Object sub = payload.get("sub");
            if (sub != null && !sub.toString().isBlank()) {
                return sub.toString();
            }
            Object username = payload.get("username");
            if (username != null && !username.toString().isBlank()) {
                return username.toString();
            }
            throw new IllegalArgumentException("Subject claim not found in token");
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot extract username from token", e);
        }
    }

    private String sign(String data, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(hash);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
