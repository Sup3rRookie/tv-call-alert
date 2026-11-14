package dev.tvcallalert.core;

/**
 * Tracks relay time from the timestamps on live relay events, so freshness checks do not
 * depend on the TV's own wall clock.
 */
public final class RelayClock {
    private boolean synced;
    private long serverSeconds;
    private long atElapsedMillis;

    /** Record a live relay event (open or keepalive) seen at a monotonic local time. */
    public synchronized void sync(long serverSeconds, long elapsedMillis) {
        this.serverSeconds = serverSeconds;
        this.atElapsedMillis = elapsedMillis;
        this.synced = true;
    }

    public synchronized boolean isSynced() {
        return synced;
    }

    /** Current relay time, or -1 when no relay event has been seen yet. */
    public synchronized long nowSeconds(long elapsedMillis) {
        if (!synced) {
            return -1;
        }
        return serverSeconds + Math.max(0, elapsedMillis - atElapsedMillis) / 1000;
    }
}
