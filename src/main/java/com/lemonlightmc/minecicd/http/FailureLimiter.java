package com.lemonlightmc.minecicd.http;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, expiring per-IP failure counter used to rate-limit unauthenticated control
 * requests (M-07/S-08). Entries older than the window are removed lazily on access and
 * eagerly when the map would exceed {@code maxEntries}, so the cache cannot grow without
 * bound through a stream of distinct invalid clients.
 */
final class FailureLimiter {

    private final ConcurrentHashMap<String, long[]> counts = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long windowMs;

    FailureLimiter(int maxEntries, long windowMs) {
        this.maxEntries = maxEntries;
        this.windowMs = windowMs;
    }

    /**
     * @return true when the IP has exceeded the allowed failures within the current window
     */
    boolean isRateLimited(String ip, long now) {
        long[] entry = counts.get(ip);
        if (entry == null) {
            return false;
        }
        if (now - entry[1] < windowMs) {
            return entry[0] >= 5;
        }
        // Window expired: drop the entry so a legitimate client can recover.
        counts.remove(ip);
        return false;
    }

    /**
     * Records a failed attempt for the given IP, enforcing the hard entry cap.
     * When the map is full, expired entries are purged first; if still full, the oldest
     * entry is evicted so the cache size stays bounded.
     */
    void recordFailure(String ip, long now) {
        counts.compute(ip, (k, v) -> {
            if (v == null || now - v[1] >= windowMs) {
                return new long[]{1, now};
            }
            v[0]++;
            return v;
        });
        if (counts.size() > maxEntries) {
            purgeExpired(now);
        }
        if (counts.size() > maxEntries) {
            evictOldest();
        }
    }

    void purgeExpired(long now) {
        Iterator<Map.Entry<String, long[]>> it = counts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, long[]> e = it.next();
            if (now - e.getValue()[1] >= windowMs) {
                it.remove();
            }
        }
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldestStart = Long.MAX_VALUE;
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            long start = e.getValue()[1];
            if (start < oldestStart) {
                oldestStart = start;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            counts.remove(oldestKey);
        }
    }

    int size() {
        return counts.size();
    }
}