package dev.tvcallalert.core;

import java.util.ArrayDeque;

/** Sliding-window limiter: a cooldown, a short window cap and a daily cap. */
public final class RateLimiter {
    private final long cooldownSeconds;
    private final int perWindow;
    private final long windowSeconds;
    private final int perDay;
    private final ArrayDeque<Long> accepted = new ArrayDeque<>();

    public RateLimiter(long cooldownSeconds, int perWindow, long windowSeconds, int perDay) {
        this.cooldownSeconds = cooldownSeconds;
        this.perWindow = perWindow;
        this.windowSeconds = windowSeconds;
        this.perDay = perDay;
    }

    public static RateLimiter forAlerts() {
        return new RateLimiter(Limits.ALERT_COOLDOWN_SECONDS, Limits.ALERTS_PER_WINDOW,
                Limits.ALERT_WINDOW_SECONDS, Limits.ALERTS_PER_DAY);
    }

    public static RateLimiter forPings() {
        return new RateLimiter(Limits.PING_COOLDOWN_SECONDS, 20, 60, 2000);
    }

    /** Returns true and counts the event when it is allowed at {@code nowSeconds}. */
    public boolean tryAcquire(long nowSeconds) {
        while (!accepted.isEmpty()
                && nowSeconds - accepted.peekFirst() >= Limits.DAY_SECONDS) {
            accepted.removeFirst();
        }
        if (accepted.size() >= perDay) {
            return false;
        }
        int inWindow = 0;
        for (long t : accepted) {
            // A timestamp ahead of now means the clock stepped back: it counts as recent.
            long age = nowSeconds - t;
            if (age < cooldownSeconds) {
                return false;
            }
            if (age < windowSeconds) {
                inWindow++;
            }
        }
        if (inWindow >= perWindow) {
            return false;
        }
        accepted.addLast(nowSeconds);
        return true;
    }
}
