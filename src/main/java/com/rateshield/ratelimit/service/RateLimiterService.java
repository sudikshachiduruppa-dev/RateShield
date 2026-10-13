package com.rateshield.ratelimit.service;

import com.rateshield.ratelimit.config.RateLimiterProperties;
import com.rateshield.ratelimit.model.TokenBucket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing per-client Token Buckets with bounded cache eviction.
 *
 * Each authenticated client has an independent TokenBucket instance.
 * To prevent memory exhaustion (unbounded map growth), buckets are bounded by:
 * 1. An idle TTL (evicting clients inactive for > idleTtlSeconds).
 * 2. A max capacity threshold (evicting the oldest idle entries when capacity is reached).
 */
@Service
public class RateLimiterService {

    private final RateLimiterProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, ClientBucketEntry> clientBuckets;

    /**
     * Primary constructor explicitly autowired for Spring dependency injection.
     */
    @Autowired
    public RateLimiterService(RateLimiterProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * Public secondary constructor for deterministic unit tests requiring an injectable clock.
     */
    public RateLimiterService(RateLimiterProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.clientBuckets = new ConcurrentHashMap<>();
    }

    /**
     * Consumes one token for the given authenticated client ID (username).
     *
     * @param clientId validated identity of the client
     * @return ConsumptionResult containing allowed flag, remaining tokens, and retry-after.
     */
    public TokenBucket.ConsumptionResult consumeClientToken(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID cannot be null or empty");
        }

        // Periodic maintenance if map exceeds configured capacity threshold
        if (clientBuckets.size() > properties.getMaxClients()) {
            evictIdleBuckets();
        }

        ClientBucketEntry entry = clientBuckets.compute(clientId, (key, existing) -> {
            if (existing == null) {
                TokenBucket newBucket = new TokenBucket(
                        properties.getCapacity(),
                        properties.getRefillTokensPerSecond(),
                        clock
                );
                return new ClientBucketEntry(newBucket, clock.instant());
            } else {
                existing.lastAccess = clock.instant();
                return existing;
            }
        });

        return entry.bucket.tryConsume();
    }

    /**
     * Evicts buckets that have been inactive longer than idleTtlSeconds.
     */
    public void evictIdleBuckets() {
        Instant cutoff = clock.instant().minusSeconds(properties.getIdleTtlSeconds());
        clientBuckets.entrySet().removeIf(entry -> entry.getValue().lastAccess.isBefore(cutoff));

        // If still over maxClients, evict oldest entries
        if (clientBuckets.size() > properties.getMaxClients()) {
            clientBuckets.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> a.lastAccess.compareTo(b.lastAccess)))
                    .limit(clientBuckets.size() - properties.getMaxClients())
                    .forEach(entry -> clientBuckets.remove(entry.getKey()));
        }
    }

    public int getActiveClientCount() {
        return clientBuckets.size();
    }

    public RateLimiterProperties getProperties() {
        return properties;
    }

    /**
     * Container holding a TokenBucket and its last activity timestamp.
     */
    static class ClientBucketEntry {
        final TokenBucket bucket;
        volatile Instant lastAccess;

        ClientBucketEntry(TokenBucket bucket, Instant lastAccess) {
            this.bucket = bucket;
            this.lastAccess = lastAccess;
        }
    }
}
