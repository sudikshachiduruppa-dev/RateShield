package com.rateshield.ratelimit;

import com.rateshield.ratelimit.model.TokenBucket;

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

class TokenBucketTest {

    /**
     * Mutable clock helper for deterministic time progression in tests.
     */
    static class TestClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        public TestClock(Instant initialInstant) {
            this.now = initialInstant;
            this.zone = ZoneId.of("UTC");
        }

        public void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("Requests within capacity should be allowed and decrement tokens")
    void testRequestsWithinCapacity() {
        TestClock clock = new TestClock(Instant.parse("2026-10-11T12:00:00Z"));
        TokenBucket bucket = new TokenBucket(5, 1.0, clock);

        for (int i = 0; i < 5; i++) {
            TokenBucket.ConsumptionResult result = bucket.tryConsume();
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
            assertEquals(4 - i, result.remainingTokens());
        }
    }

    @Test
    @DisplayName("Requests exceeding capacity should be rejected when no tokens are available")
    void testRequestsExceedingCapacity() {
        TestClock clock = new TestClock(Instant.parse("2026-10-11T12:00:00Z"));
        TokenBucket bucket = new TokenBucket(5, 1.0, clock);

        // Consume 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryConsume().allowed());
        }

        // 6th request must be rejected
        TokenBucket.ConsumptionResult sixth = bucket.tryConsume();
        assertFalse(sixth.allowed(), "6th request without refill should be rejected");
        assertEquals(0, sixth.remainingTokens());
        assertEquals(1, sixth.retryAfterSeconds());

        // 7th request also rejected
        TokenBucket.ConsumptionResult seventh = bucket.tryConsume();
        assertFalse(seventh.allowed(), "7th request should also be rejected");
    }

    @Test
    @DisplayName("Tokens should refill over elapsed time according to refill rate")
    void testTokenRefillOverTime() {
        TestClock clock = new TestClock(Instant.parse("2026-10-11T12:00:00Z"));
        // 5 capacity, 1 token per second
        TokenBucket bucket = new TokenBucket(5, 1.0, clock);

        // Exhaust all 5 tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryConsume().allowed());
        }
        assertFalse(bucket.tryConsume().allowed());

        // Advance 2 seconds -> should add 2 tokens
        clock.advance(Duration.ofSeconds(2));

        assertTrue(bucket.tryConsume().allowed(), "First refilled token should be consumed");
        assertTrue(bucket.tryConsume().allowed(), "Second refilled token should be consumed");
        assertFalse(bucket.tryConsume().allowed(), "Third request should be rejected as only 2 refilled");
    }

    @Test
    @DisplayName("Tokens should not exceed maximum bucket capacity during refill")
    void testBucketCapacityEnforcement() {
        TestClock clock = new TestClock(Instant.parse("2026-10-11T12:00:00Z"));
        TokenBucket bucket = new TokenBucket(3, 1.0, clock);

        // Let 100 seconds pass with idle bucket
        clock.advance(Duration.ofSeconds(100));

        // Available tokens must still be capped at 3
        assertEquals(3.0, bucket.getAvailableTokens(), 0.001);

        assertTrue(bucket.tryConsume().allowed());
        assertTrue(bucket.tryConsume().allowed());
        assertTrue(bucket.tryConsume().allowed());
        assertFalse(bucket.tryConsume().allowed(), "Bucket capacity of 3 should only allow 3 requests");
    }

    @Test
    @DisplayName("Concurrent requests should be handled safely without race conditions")
    void testConcurrentRequests() throws InterruptedException {
        TestClock clock = new TestClock(Instant.parse("2026-10-11T12:00:00Z"));
        final int capacity = 50;
        final int totalThreads = 100;
        TokenBucket bucket = new TokenBucket(capacity, 0.0001, clock); // negligible refill rate

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalThreads);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TokenBucket.ConsumptionResult res = bucket.tryConsume();
                    if (res.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertEquals(capacity, allowedCount.get(), "Exactly capacity (50) requests must be allowed");
        assertEquals(totalThreads - capacity, rejectedCount.get(), "Exactly 50 requests must be rejected");
    }

    @Test
    @DisplayName("Independent bucket instances should maintain separate state")
    void testIndependentBucketInstances() {
        TestClock clock = new TestClock(Instant.parse("2026-10-11T12:00:00Z"));
        TokenBucket bucketA = new TokenBucket(2, 1.0, clock);
        TokenBucket bucketB = new TokenBucket(2, 1.0, clock);

        // Exhaust bucket A
        assertTrue(bucketA.tryConsume().allowed());
        assertTrue(bucketA.tryConsume().allowed());
        assertFalse(bucketA.tryConsume().allowed(), "Bucket A should be empty");

        // Bucket B should remain completely untouched
        assertTrue(bucketB.tryConsume().allowed(), "Bucket B should still have tokens");
        assertTrue(bucketB.tryConsume().allowed(), "Bucket B should still have tokens");
        assertFalse(bucketB.tryConsume().allowed(), "Bucket B should now be empty");
    }
}
