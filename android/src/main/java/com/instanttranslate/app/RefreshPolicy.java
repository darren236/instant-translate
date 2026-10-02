package com.instanttranslate.app;

/** Keeps a changed screen pending until it has been quiet for the requested time. */
final class RefreshPolicy {
    private final long quietDelayMs;
    private boolean dirty;
    private long lastChangeAt;

    RefreshPolicy(long quietDelayMs) {
        if (quietDelayMs < 0) throw new IllegalArgumentException("Quiet delay must be nonnegative");
        this.quietDelayMs = quietDelayMs;
    }

    /** Call for actual content motion, using a monotonic clock such as uptimeMillis. */
    synchronized void changed(long now) {
        dirty = true;
        lastChangeAt = now;
    }

    /** Reading readiness does not renew the quiet period. A clean screen needs no wait. */
    synchronized long delayUntilReady(long now) {
        if (!dirty) return 0;
        long elapsed = Math.max(0, now - lastChangeAt);
        return Math.max(0, quietDelayMs - elapsed);
    }

    synchronized boolean isDirty() {
        return dirty;
    }

    /** Clear only once a new source frame has been acquired, not when inference finishes. */
    synchronized void captured() {
        reset();
    }

    synchronized void reset() {
        dirty = false;
        lastChangeAt = 0;
    }
}
