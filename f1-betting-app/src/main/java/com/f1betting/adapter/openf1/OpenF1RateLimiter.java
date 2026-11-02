package com.f1betting.adapter.openf1;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client-side rate limiter for outbound requests to the OpenF1 API.
 * Powered by Resilience4j {@link RateLimiter} and {@link CircuitBreaker} with adaptive 429 Retry-After handling.
 *
 * <p>OpenF1 free tier enforces:
 * <ul>
 *   <li>3 requests per second burst limit</li>
 *   <li>~30 requests per minute sustained limit</li>
 * </ul>
 *
 * <p>This component enforces:
 * <ol>
 *   <li>Resilience4j sliding-window rate limiter per 60 seconds (default: 25 requests)</li>
 *   <li>Resilience4j circuit breaker tracking failures with automatic half-open transitions</li>
 *   <li>Inter-request throttle delay (>= 350ms between calls to stay under 3 req/sec)</li>
 *   <li>Dynamic HTTP 429 backoff honoring upstream Retry-After headers</li>
 * </ol>
 */
@Component
@Slf4j
public class OpenF1RateLimiter {

    @Getter
    private final int requestsPerSecond;
    @Getter
    private final int requestsPerMinute;
    private final long minIntervalMs;
    private final long defaultCooldownMs;
    private final long failureCoolDownMs;

    private final RateLimiterConfig rateLimiterConfig;
    @Getter
    private volatile RateLimiter resilienceRateLimiter;
    @Getter
    private final CircuitBreaker resilienceCircuitBreaker;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile long cooldownUntil = 0L;
    private long lastRequestTime = 0L;

    public OpenF1RateLimiter(
            @Value("${openf1.rate-limit.requests-per-second:3}") int requestsPerSecond,
            @Value("${openf1.rate-limit.requests-per-minute:25}") int requestsPerMinute,
            @Value("${openf1.rate-limit.cooldown-seconds:60}") int cooldownSeconds,
            @Value("${openf1.rate-limit.failure-cooldown-seconds:20}") int failureCoolDownSeconds) {
        this.requestsPerSecond = Math.max(1, requestsPerSecond);
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.minIntervalMs = (1000L / this.requestsPerSecond) + 35L;
        this.defaultCooldownMs = Math.max(1, cooldownSeconds) * 1000L;
        this.failureCoolDownMs = Math.max(1, failureCoolDownSeconds) * 1000L;

        this.rateLimiterConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(60))
                .limitForPeriod(this.requestsPerMinute)
                .timeoutDuration(Duration.ZERO)
                .build();
        this.resilienceRateLimiter = RateLimiter.of("openF1RateLimiter", rateLimiterConfig);

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(this.defaultCooldownMs))
                .build();
        this.resilienceCircuitBreaker = CircuitBreaker.of("openF1CircuitBreaker", circuitBreakerConfig);
    }

    /**
     * Checks if a request can be permitted right now without exceeding limits.
     * Non-blocking check backed by Resilience4j.
     *
     * @return true if rate limit budget has capacity and cooldown is not active
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < cooldownUntil || resilienceCircuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                return false;
            }
            return resilienceRateLimiter.getMetrics().getAvailablePermissions() > 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire permission to make an outbound HTTP request.
     * Consumes a Resilience4j rate-limiter token, verifies circuit breaker state,
     * and pauses briefly if necessary to satisfy the per-second burst throttle.
     *
     * @return true if permission granted, false if rejected due to rate limits or cooldown
     */
    public boolean acquireWithThrottle() {
        long sleepMs = 0L;
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < cooldownUntil || resilienceCircuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                log.debug("OpenF1 rate limiter: cooldown/circuit breaker active");
                return false;
            }

            if (!resilienceRateLimiter.acquirePermission()) {
                log.warn("OpenF1 rate limiter: 60s request budget reached ({} requests). Proactively switching to fallback data.", requestsPerMinute);
                return false;
            }

            long elapsed = now - lastRequestTime;
            if (elapsed < minIntervalMs) {
                sleepMs = minIntervalMs - elapsed;
            }

            long scheduledTime = now + sleepMs;
            lastRequestTime = scheduledTime;
        } finally {
            lock.unlock();
        }

        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limiter sleep interrupted");
                return false;
            }
        }
        return true;
    }

    /**
     * Activates cooldown backoff when OpenF1 returns HTTP 429 Too Many Requests.
     *
     * @param retryAfterSeconds optional Retry-After header in seconds (null to use default cooldown)
     */
    public void recordRateLimit429(Long retryAfterSeconds) {
        long durationMs = (retryAfterSeconds != null && retryAfterSeconds > 0)
                ? retryAfterSeconds * 1000L
                : defaultCooldownMs;
        lock.lock();
        try {
            this.cooldownUntil = System.currentTimeMillis() + durationMs;
            resilienceCircuitBreaker.transitionToOpenState();
            log.warn("OpenF1 HTTP 429 received. Cooldown active for {} seconds.", durationMs / 1000);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Activates a short cooldown after a network or timeout failure.
     */
    public void recordFailure() {
        lock.lock();
        try {
            long candidate = System.currentTimeMillis() + failureCoolDownMs;
            if (candidate > this.cooldownUntil) {
                this.cooldownUntil = candidate;
            }
            resilienceCircuitBreaker.onError(0, TimeUnit.MILLISECONDS, new RuntimeException("OpenF1 network/timeout failure"));
            log.warn("OpenF1 request failed (network/timeout error). Activating {}s cooldown", failureCoolDownMs / 1000);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if 429 cooldown or circuit breaker is currently active.
     */
    public boolean isCooldownActive() {
        return System.currentTimeMillis() < cooldownUntil || resilienceCircuitBreaker.getState() == CircuitBreaker.State.OPEN;
    }

    /**
     * Returns remaining requests allowed in the current rolling 60-second window.
     */
    public int getAvailableMinuteTokens() {
        lock.lock();
        try {
            if (isCooldownActive()) {
                return 0;
            }
            return resilienceRateLimiter.getMetrics().getAvailablePermissions();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets rate limiter and circuit breaker state (useful for tests).
     */
    public void reset() {
        lock.lock();
        try {
            cooldownUntil = 0L;
            lastRequestTime = 0L;
            resilienceCircuitBreaker.reset();
            this.resilienceRateLimiter = RateLimiter.of("openF1RateLimiter", rateLimiterConfig);
        } finally {
            lock.unlock();
        }
    }
}
