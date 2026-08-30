package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.pending.PendingRequest.Status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request terminal status, available for the GitHub Action to poll after the SSE
 * stream drops (e.g. a mid-sequence restart). Also carries an in-memory event counter
 * so recent progress can be replayed to a freshly-attached SSE client.
 */
public class ControlStatus {

    public record Entry(Status status, String error, int completedActions, int totalActions, long updatedAt) {
    }

    private final Map<String, Entry> statuses = new ConcurrentHashMap<>();
    private final Map<String, Integer> eventCounters = new ConcurrentHashMap<>();

    public void update(String requestId, Status status, String error, int completed, int total) {
        statuses.put(requestId, new Entry(status, error, completed, total, System.currentTimeMillis()));
    }

    public void bump(String requestId) {
        eventCounters.merge(requestId, 1, Integer::sum);
    }

    public Entry get(String requestId) {
        return statuses.get(requestId);
    }

    public int eventCount(String requestId) {
        return eventCounters.getOrDefault(requestId, 0);
    }

    public void clear(String requestId) {
        statuses.remove(requestId);
        eventCounters.remove(requestId);
    }
}