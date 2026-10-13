package com.rateshield.ratelimit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rateshield.rate-limit")
public class RateLimiterProperties {

    /**
     * Maximum number of tokens a bucket can hold.
     */
    private long capacity = 5;

    /**
     * Refill rate in tokens per second.
     */
    private double refillTokensPerSecond = 1.0;

    /**
     * Maximum number of active client buckets to keep in memory before evicting idle buckets.
     */
    private int maxClients = 10000;

    /**
     * Time-to-live (in seconds) for an idle client bucket before eviction.
     */
    private long idleTtlSeconds = 1800; // 30 minutes default

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public double getRefillTokensPerSecond() {
        return refillTokensPerSecond;
    }

    public void setRefillTokensPerSecond(double refillTokensPerSecond) {
        this.refillTokensPerSecond = refillTokensPerSecond;
    }

    public int getMaxClients() {
        return maxClients;
    }

    public void setMaxClients(int maxClients) {
        this.maxClients = maxClients;
    }

    public long getIdleTtlSeconds() {
        return idleTtlSeconds;
    }

    public void setIdleTtlSeconds(long idleTtlSeconds) {
        this.idleTtlSeconds = idleTtlSeconds;
    }
}
