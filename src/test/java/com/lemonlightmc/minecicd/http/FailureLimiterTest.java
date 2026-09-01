package com.lemonlightmc.minecicd.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bounded, expiring per-IP failure limiter used by ControlServer (S-08).
 */
class FailureLimiterTest {

    private static final long WINDOW = 10_000L;

    @Test
    void rateLimitsAfterFiveFailuresWithinWindow() {
        FailureLimiter limiter = new FailureLimiter(100, WINDOW);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.isRateLimited("1.2.3.4", now));
            limiter.recordFailure("1.2.3.4", now);
        }
        assertTrue(limiter.isRateLimited("1.2.3.4", now));
    }

    @Test
    void windowExpiryResetsTheCounter() {
        FailureLimiter limiter = new FailureLimiter(100, WINDOW);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4", now);
        }
        assertTrue(limiter.isRateLimited("1.2.3.4", now));
        // after the window, the IP is no longer rate limited
        assertFalse(limiter.isRateLimited("1.2.3.4", now + WINDOW + 1));
    }

    @Test
    void expiredEntriesArePurged() {
        FailureLimiter limiter = new FailureLimiter(100, WINDOW);
        long now = 1_000_000L;
        limiter.recordFailure("a", now);
        limiter.recordFailure("b", now);
        assertEquals(2, limiter.size());
        limiter.purgeExpired(now + WINDOW + 1);
        assertEquals(0, limiter.size());
    }

    @Test
    void distinctClientsCannotGrowCacheBeyondCap() {
        FailureLimiter limiter = new FailureLimiter(10, WINDOW);
        long now = 1_000_000L;
        for (int i = 0; i < 100; i++) {
            limiter.recordFailure("client-" + i, now);
        }
        assertTrue(limiter.size() <= 10, "cache must stay bounded: " + limiter.size());
    }

    @Test
    void unknownIpIsHandledLikeAnyOther() {
        FailureLimiter limiter = new FailureLimiter(100, WINDOW);
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("unknown", now);
        }
        assertTrue(limiter.isRateLimited("unknown", now));
    }

    @Test
    void evictsOldestWhenFullOfFreshEntries() {
        FailureLimiter limiter = new FailureLimiter(2, WINDOW);
        long now = 1_000_000L;
        limiter.recordFailure("old", now);
        limiter.recordFailure("new", now + 1);
        limiter.recordFailure("third", now + 2);
        assertEquals(2, limiter.size());
        // The oldest entry (windowStart = now) must have been evicted.
        assertFalse(limiter.isRateLimited("old", now + 2),
                "oldest entry should be evicted under the cap");
    }
}