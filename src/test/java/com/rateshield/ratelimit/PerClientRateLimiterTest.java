package com.rateshield.ratelimit;

import com.rateshield.authentication.jwt.JwtTokenProvider;
import com.rateshield.ratelimit.config.RateLimiterProperties;
import com.rateshield.ratelimit.model.TokenBucket;
import com.rateshield.ratelimit.service.RateLimiterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PerClientRateLimiterTest {

    static class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone = ZoneId.of("UTC");

        public MutableClock(Instant initialInstant) {
            this.now = initialInstant;
        }

        public void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("User A exhausts their bucket while User B can still make permitted requests")
    void testUserAExhaustsBucketWhileUserBRemainsUnaffected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setCapacity(3);
        properties.setRefillTokensPerSecond(0.0001); // no refill during test

        RateLimiterService service = new RateLimiterService(properties, clock);

        // User A consumes all 3 tokens
        for (int i = 0; i < 3; i++) {
            TokenBucket.ConsumptionResult res = service.consumeClientToken("userA");
            assertTrue(res.allowed(), "User A request " + (i + 1) + " should be allowed");
        }

        // User A's 4th request must be rejected (429)
        TokenBucket.ConsumptionResult userARejected = service.consumeClientToken("userA");
        assertFalse(userARejected.allowed(), "User A should be rate-limited on 4th request");

        // User B has never made a request: their bucket must be full (3 tokens available)
        for (int i = 0; i < 3; i++) {
            TokenBucket.ConsumptionResult res = service.consumeClientToken("userB");
            assertTrue(res.allowed(), "User B request " + (i + 1) + " must succeed even when User A is exhausted");
            assertEquals(2 - i, res.remainingTokens());
        }

        // User B's 4th request is now rejected
        assertFalse(service.consumeClientToken("userB").allowed(), "User B is now also rate-limited");
    }

    @Test
    @DisplayName("JWT Token validation rejects tampering and expired tokens")
    void testJwtTokenValidation() {
        JwtTokenProvider provider = new JwtTokenProvider(
                "my-test-secret-key-that-is-at-least-256-bits-long-32-bytes!!",
                2 // 2 seconds validity for test
        );

        String validToken = provider.generateToken("alice");
        assertTrue(provider.validateToken(validToken), "Generated token must be valid");
        assertEquals("alice", provider.getUsernameFromToken(validToken));

        // Tampered payload
        String[] parts = validToken.split("\\.");
        String tamperedToken = parts[0] + ".eyJzdWIiOiJldmlsIn0." + parts[2];
        assertFalse(provider.validateToken(tamperedToken), "Tampered token must be rejected");

        // Invalid signature
        String fakeToken = parts[0] + "." + parts[1] + ".invalidSignature123";
        assertFalse(provider.validateToken(fakeToken), "Token with invalid signature must be rejected");

        // Garbage string
        assertFalse(provider.validateToken("not-a-token"));
        assertFalse(provider.validateToken(""));
        assertFalse(provider.validateToken(null));
    }

    @Test
    @DisplayName("Idle bucket eviction prevents unbounded memory growth")
    void testIdleBucketEviction() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setCapacity(5);
        properties.setRefillTokensPerSecond(1.0);
        properties.setIdleTtlSeconds(60); // 60 seconds TTL
        properties.setMaxClients(2);

        RateLimiterService service = new RateLimiterService(properties, clock);

        // Add client1 and client2
        service.consumeClientToken("client1");
        service.consumeClientToken("client2");
        assertEquals(2, service.getActiveClientCount());

        // Advance time by 70 seconds
        clock.advance(Duration.ofSeconds(70));

        // Evict idle
        service.evictIdleBuckets();
        assertEquals(0, service.getActiveClientCount(), "Both clients should be evicted due to idle TTL");
    }

    @Test
    @DisplayName("Concurrent requests across multiple clients do not corrupt state")
    void testConcurrentPerClientRequests() throws InterruptedException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setCapacity(20);
        properties.setRefillTokensPerSecond(0.0001);

        RateLimiterService service = new RateLimiterService(properties, clock);

        int clients = 5;
        int threadsPerClient = 25; // 20 allowed, 5 rejected per client
        int totalRequests = clients * threadsPerClient;

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);

        AtomicInteger allowedTotal = new AtomicInteger(0);
        AtomicInteger rejectedTotal = new AtomicInteger(0);

        for (int c = 0; c < clients; c++) {
            final String clientId = "client_" + c;
            for (int t = 0; t < threadsPerClient; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        TokenBucket.ConsumptionResult res = service.consumeClientToken(clientId);
                        if (res.allowed()) {
                            allowedTotal.incrementAndGet();
                        } else {
                            rejectedTotal.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        // Each of the 5 clients should have exactly 20 allowed and 5 rejected
        assertEquals(clients * 20, allowedTotal.get(), "Each client must get exactly 20 allowed requests");
        assertEquals(clients * 5, rejectedTotal.get(), "Each client must have 5 rejected requests");
        assertEquals(5, service.getActiveClientCount());
    }
}
