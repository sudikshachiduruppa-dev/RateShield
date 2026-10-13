package com.rateshield.authentication.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtTokenProvider.
 *
 * These tests exercise the full generate → validate → extract round-trip
 * WITHOUT Spring context, proving the algorithm is self-consistent.
 */
class JwtTokenProviderTest {

    // Use the same default secret that application.properties configures
    private final JwtTokenProvider provider = new JwtTokenProvider(
            "rateshield-super-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256",
            86400L
    );

    @Test
    @DisplayName("Freshly generated token passes validation immediately")
    void generatedTokenValidatesSuccessfully() {
        String token = provider.generateToken("alice");
        assertNotNull(token, "generateToken must return a non-null string");
        assertTrue(provider.validateToken(token),
                "A token generated right now must be valid (not expired, signature correct)");
    }

    @Test
    @DisplayName("Username survives the generate → extract round-trip")
    void usernameRoundtrip() {
        String token = provider.generateToken("bob");
        assertEquals("bob", provider.getUsernameFromToken(token),
                "getUsernameFromToken must return the same username used in generateToken");
    }

    @Test
    @DisplayName("Multiple different usernames each produce distinct valid tokens")
    void distinctUsersProduceDistinctTokens() {
        String t1 = provider.generateToken("user1");
        String t2 = provider.generateToken("user2");
        assertNotEquals(t1, t2, "Different users must produce different tokens");
        assertEquals("user1", provider.getUsernameFromToken(t1));
        assertEquals("user2", provider.getUsernameFromToken(t2));
    }

    @Test
    @DisplayName("Null and blank tokens are rejected")
    void nullAndBlankTokensAreRejected() {
        assertFalse(provider.validateToken(null));
        assertFalse(provider.validateToken(""));
        assertFalse(provider.validateToken("   "));
    }

    @Test
    @DisplayName("Tokens with wrong structure are rejected")
    void malformedTokensAreRejected() {
        assertFalse(provider.validateToken("not-a-jwt"));
        assertFalse(provider.validateToken("only.two-parts"));
        assertFalse(provider.validateToken("a.b.c.d")); // four parts
    }

    @Test
    @DisplayName("Tampered signature is rejected")
    void tamperedSignatureIsRejected() {
        String token = provider.generateToken("carol");
        // Replace the last character of the signature with a different one
        char last = token.charAt(token.length() - 1);
        char replacement = (last == 'A') ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        assertFalse(provider.validateToken(tampered),
                "A token with a tampered signature must be rejected");
    }

    @Test
    @DisplayName("Expired token is rejected")
    void expiredTokenIsRejected() {
        // Provider with 0-second validity → token expires immediately
        JwtTokenProvider shortLived = new JwtTokenProvider(
                "rateshield-super-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256",
                0L
        );
        String token = shortLived.generateToken("dave");
        // With 0-second expiry the exp == iat, so NOW >= exp → invalid
        assertFalse(shortLived.validateToken(token),
                "A token with zero-second validity must be rejected");
    }

    @Test
    @DisplayName("Full lifecycle: generate token, validate same token, extract username")
    void fullGenerateValidateExtractCycle() {
        String username = "rateshield_user";
        String token = provider.generateToken(username);

        assertTrue(provider.validateToken(token), "Generated token must be valid");
        assertEquals(username, provider.getUsernameFromToken(token), "Extracted username must match");

        // Token with surrounding whitespace should still validate and extract
        assertTrue(provider.validateToken("  " + token + "  "));
        assertEquals(username, provider.getUsernameFromToken("  " + token + "  "));
    }

    @Test
    @DisplayName("Tampered payload is rejected by signature validation")
    void tamperedPayloadIsRejected() {
        String token = provider.generateToken("eve");
        String[] parts = token.split("\\.");
        // Modify payload part
        String tampered = parts[0] + ".eyJzdWIiOiJhZG1pbiJ9." + parts[2];
        assertFalse(provider.validateToken(tampered), "Token with tampered payload must fail validation");
    }

    @Test
    @DisplayName("Blank or too-short secret key is rejected on instantiation")
    void weakSecretIsRejected() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(null, 86400L));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("", 86400L));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("   ", 86400L));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("short-secret-under-32-bytes", 86400L));
    }
}
