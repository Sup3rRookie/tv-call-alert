package dev.tvcallalert.core;

/** Why an incoming message was not acted on. */
public enum Rejection {
    TOO_LARGE,
    BAD_CHARSET,
    BAD_FORMAT,
    UNKNOWN_TOPIC,
    SLOT_MISMATCH,
    BAD_SIGNATURE,
    STALE,
    FUTURE,
    REPLAYED,
    RATE_LIMITED
}
