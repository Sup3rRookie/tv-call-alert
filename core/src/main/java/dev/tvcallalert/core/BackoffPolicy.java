package dev.tvcallalert.core;

import java.util.Random;

/** Exponential reconnect backoff with jitter. */
public final class BackoffPolicy {
    public static final long BASE_MILLIS = 1000;
    public static final long CAP_MILLIS = 60_000;
    public static final long SERVER_ERROR_CAP_MILLIS = 300_000;
    public static final long HEALTHY_MILLIS = 30_000;
    private static final int MAX_SHIFT = 20;

    private final Random random;
    private int failures;

    public BackoffPolicy(Random random) {
        this.random = random;
    }

    /**
     * Delay before the next attempt.
     *
     * @param serverPushedBack true after HTTP 429 or 5xx, which allows a longer cap
     */
    public synchronized long nextDelayMillis(boolean serverPushedBack) {
        long cap = serverPushedBack ? SERVER_ERROR_CAP_MILLIS : CAP_MILLIS;
        long ceiling = Math.min(cap, BASE_MILLIS << Math.min(failures, MAX_SHIFT));
        if (failures < MAX_SHIFT) {
            failures++;
        }
        // Jittered, but never below a quarter of the ceiling so a tight loop is impossible.
        long floor = ceiling / 4;
        return floor + (long) (random.nextDouble() * (ceiling - floor));
    }

    /** Call when a connection ended; resets only if it stayed up long enough. */
    public synchronized void connectionEnded(long connectedForMillis) {
        if (connectedForMillis >= HEALTHY_MILLIS) {
            failures = 0;
        }
    }

    /** Call when the network came back or the screen turned on. */
    public synchronized void reset() {
        failures = 0;
    }
}
