package com.rateshield.ratelimit.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Custom in-memory Token Bucket rate limiter.
 *
 * Safe for concurrent access.
 * Supports an injectable java.time.Clock for deterministic testing without Thread.sleep().
 */
public class TokenBucket {

    private final long capacity;
    private final double refillTokensPerSecond;
    private final Clock clock;

    private double availableTokens;
    private Instant lastRefillTimestamp;

    /**
     * Creates a new TokenBucket initialized to full capacity.
     *
     * @param capacity maximum token capacity of the bucket (>= 1)
     * @param refillTokensPerSecond rate of tokens replenished per second (> 0)
     * @param clock clock used to measure elapsed time
     */
    public TokenBucket(long capacity, double refillTokensPerSecond, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        if (refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be greater than zero");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock cannot be null");
        }

        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
        this.clock = clock;
        this.availableTokens = capacity;
        this.lastRefillTimestamp = clock.instant();
    }

    /**
     * Creates a TokenBucket using system UTC clock.
     */
    public TokenBucket(long capacity, double refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, Clock.systemUTC());
    }

    /**
     * Attempts to consume one token.
     *
     * @return ConsumptionResult containing whether consumption succeeded, remaining tokens,
     *         and seconds until next refill.
     */
    public synchronized ConsumptionResult tryConsume() {
        refill();

        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            long remaining = (long) Math.floor(availableTokens);
            return ConsumptionResult.allowed(remaining, capacity);
        } else {
            double tokensNeeded = 1.0 - availableTokens;
            long retryAfterSeconds = (long) Math.ceil(tokensNeeded / refillTokensPerSecond);
            if (retryAfterSeconds < 1) {
                retryAfterSeconds = 1;
            }
            return ConsumptionResult.rejected(capacity, retryAfterSeconds);
        }
    }

    /**
     * Refills tokens based on elapsed time since last refill.
     */
    private void refill() {
        Instant now = clock.instant();
        Duration elapsed = Duration.between(lastRefillTimestamp, now);

        if (elapsed.isNegative()) {
            // Guard against clock skew or backwards jump
            return;
        }

        double elapsedSeconds = elapsed.toNanos() / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            double tokensToAdd = elapsedSeconds * refillTokensPerSecond;
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }

    public synchronized double getAvailableTokens() {
        refill();
        return availableTokens;
    }

    public long getCapacity() {
        return capacity;
    }

    public double getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    /**
     * Immutable result of a tryConsume operation.
     */
    public record ConsumptionResult(
            boolean allowed,
            long remainingTokens,
            long capacity,
            long retryAfterSeconds
    ) {
        public static ConsumptionResult allowed(long remainingTokens, long capacity) {
            return new ConsumptionResult(true, remainingTokens, capacity, 0);
        }

        public static ConsumptionResult rejected(long capacity, long retryAfterSeconds) {
            return new ConsumptionResult(false, 0, capacity, retryAfterSeconds);
        }
    }
}
