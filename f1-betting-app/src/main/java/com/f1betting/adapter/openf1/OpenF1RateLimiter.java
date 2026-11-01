package com.f1betting.adapter.openf1;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client-side rate limiter for outbound requests to the OpenF1 API.
 *
 * <p>OpenF1 free tier enforces:
 * <ul>
 *   <li>3 requests per second burst limit</li>
 *   <li>~30 requests per minute sustained limit</li>
 * </ul>
 *
 * <p>This rate limiter enforces:
 * <ol>
 *   <li>Inter-request throttle delay (e.g. >= 350ms between requests to strictly stay under 3 req/sec)</li>
 *   <li>Sliding-window cap per 60 seconds (default: 25 requests, maintaining a safe buffer below 30)</li>
 *   <li>Cooldown backoff when an HTTP 429 is encountered (default: 60s or Retry-After header)</li>
 * </ol>
 */
@Component
@Slf4j
public class OpenF1RateLimiter {

    private final int requestsPerSecond;
    private final int requestsPerMinute;
    private final long minIntervalMs;
    private final long defaultCooldownMs;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Long> requestTimestamps = new ArrayDeque<>();
    private volatile long cooldownUntil = 0L;
    private long lastRequestTime = 0L;

    public OpenF1RateLimiter(
            @Value("${openf1.rate-limit.requests-per-second:3}") int requestsPerSecond,
            @Value("${openf1.rate-limit.requests-per-minute:25}") int requestsPerMinute,
            @Value("${openf1.rate-limit.cooldown-seconds:60}") int cooldownSeconds) {
        this.requestsPerSecond = Math.max(1, requestsPerSecond);
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.minIntervalMs = (1000L / this.requestsPerSecond) + 35L; // e.g. ~368ms for 3 req/s
        this.defaultCooldownMs = Math.max(1, cooldownSeconds) * 1000L;
    }

    /**
     * Checks if a request can be permitted right now without exceeding limits.
     * Non-blocking check.
     *
     * @return true if rate limit budget has capacity and cooldown is not active
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < cooldownUntil) {
                return false;
            }
            pruneOldTimestamps(now);
            return requestTimestamps.size() < requestsPerMinute;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to acquire permission to make an outbound HTTP request.
     * If within quota and not in cooldown, it pauses briefly if necessary to respect
     * the per-second rate limit, records the request, and returns true.
     * If quota is exhausted or cooldown is active, it returns false immediately.
     *
     * @return true if permission granted, false if rejected due to rate limits or cooldown
     */
    public boolean acquireWithThrottle() {
        long sleepMs = 0L;
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < cooldownUntil) {
                log.debug("OpenF1 rate limiter: cooldown active for another {}ms", cooldownUntil - now);
                return false;
            }

            pruneOldTimestamps(now);
            if (requestTimestamps.size() >= requestsPerMinute) {
                log.warn("OpenF1 rate limiter: 60s request budget reached ({} requests). Proactively switching to fallback data.", requestsPerMinute);
                return false;
            }

            long elapsed = now - lastRequestTime;
            if (elapsed < minIntervalMs) {
                sleepMs = minIntervalMs - elapsed;
            }

            long scheduledTime = now + sleepMs;
            requestTimestamps.addLast(scheduledTime);
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
            log.warn("OpenF1 HTTP 429 received. Cooldown active for {} seconds.", durationMs / 1000);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks if 429 cooldown is currently active.
     */
    public boolean isCooldownActive() {
        return System.currentTimeMillis() < cooldownUntil;
    }

    /**
     * Returns remaining requests allowed in the current rolling 60-second window.
     */
    public int getAvailableMinuteTokens() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < cooldownUntil) {
                return 0;
            }
            pruneOldTimestamps(now);
            return Math.max(0, requestsPerMinute - requestTimestamps.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets rate limiter state (useful for tests).
     */
    public void reset() {
        lock.lock();
        try {
            requestTimestamps.clear();
            cooldownUntil = 0L;
            lastRequestTime = 0L;
        } finally {
            lock.unlock();
        }
    }

    private void pruneOldTimestamps(long now) {
        long cutoff = now - 60_000L;
        while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst() <= cutoff) {
            requestTimestamps.pollFirst();
        }
    }
}
