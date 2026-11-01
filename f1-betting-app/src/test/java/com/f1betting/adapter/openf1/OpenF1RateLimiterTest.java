package com.f1betting.adapter.openf1;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenF1RateLimiterTest {

    private OpenF1RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // 3 req/sec, max 5 req/min, 2s cooldown for fast test execution
        rateLimiter = new OpenF1RateLimiter(3, 5, 2);
    }

    @Test
    void testMinuteBudgetCap() {
        assertTrue(rateLimiter.tryAcquire());
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.acquireWithThrottle(), "Request " + (i + 1) + " should succeed");
        }

        // 6th request should be rejected because minute budget (5) is exhausted
        assertFalse(rateLimiter.tryAcquire(), "tryAcquire should return false when budget is full");
        assertFalse(rateLimiter.acquireWithThrottle(), "acquireWithThrottle should return false when budget is full");
        assertEquals(0, rateLimiter.getAvailableMinuteTokens());
    }

    @Test
    void testCooldownOn429() {
        assertTrue(rateLimiter.tryAcquire());

        // Simulate 429 received with 2-second cooldown
        rateLimiter.recordRateLimit429(2L);
        assertTrue(rateLimiter.isCooldownActive());
        assertFalse(rateLimiter.tryAcquire());
        assertFalse(rateLimiter.acquireWithThrottle());
    }

    @Test
    void testReset() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.acquireWithThrottle();
        }
        assertFalse(rateLimiter.tryAcquire());

        rateLimiter.reset();
        assertTrue(rateLimiter.tryAcquire());
        assertEquals(5, rateLimiter.getAvailableMinuteTokens());
    }
}
