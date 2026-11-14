package dev.tvcallalert.core;

/** Every protocol limit in one place. */
public final class Limits {
    public static final int MAX_MESSAGE_CHARS = 200;
    public static final int MAX_SLOTS = 4;
    public static final int SEED_BYTES = 16;
    public static final int NONCE_CHARS = 22;
    public static final int MAC_CHARS = 43;
    public static final int TOPIC_HEX_CHARS = 40;
    /** A message is accepted when relayNow - ts is within [-MAX_FUTURE, MAX_AGE] seconds. */
    public static final long MAX_AGE_SECONDS = 60;
    public static final long MAX_FUTURE_SECONDS = 30;
    public static final int NONCE_CACHE_SIZE = 64;
    public static final long NONCE_TTL_SECONDS = 120;
    public static final long ALERT_COOLDOWN_SECONDS = 10;
    public static final int ALERTS_PER_WINDOW = 3;
    public static final long ALERT_WINDOW_SECONDS = 300;
    public static final int ALERTS_PER_DAY = 30;
    public static final long DAY_SECONDS = 86400;
    public static final long PING_COOLDOWN_SECONDS = 2;
    public static final int MAX_NAME_CHARS = 16;

    private Limits() {
    }
}
