package dev.tvcallalert.core;

/** Builds signed TV-to-phone acknowledgements: {@code RA1|status|slot|ts|ref|mac}. */
public final class AckBuilder {
    /** Outcome reported back to the phone. */
    public enum Status {
        SHOWN("shown"), DISMISSED("dismissed"), EXPIRED("expired"), CANCELLED("cancelled"),
        ASLEEP("asleep"), STALE("stale"), LIMITED("limited"), FAILED("failed"), PONG("pong");

        public final String wire;

        Status(String wire) {
            this.wire = wire;
        }
    }

    private AckBuilder() {
    }

    public static String build(Status status, int slot, long tsSeconds, String refNonce,
            SlotKeys keys) {
        if (slot < 1 || slot > Limits.MAX_SLOTS
                || !MessageParser.isToken(refNonce, Limits.NONCE_CHARS)) {
            throw new IllegalArgumentException("bad ack fields");
        }
        String signedPart = "RA1|" + status.wire + "|" + slot + "|" + tsSeconds + "|" + refNonce;
        return signedPart + "|" + Kdf.sign(keys.ackKey(), signedPart);
    }
}
