package dev.tvcallalert.core;

/** A syntactically valid phone-to-TV message. Not yet authenticated. */
public final class WireMessage {
    /** Message kinds a phone can send. */
    public enum Type { ALERT, CANCEL, PING }

    public final Type type;
    public final int slot;
    public final long ts;
    public final String nonce;
    /** For CANCEL the nonce of the alert to cancel, otherwise null. */
    public final String ref;
    final String signedPart;
    final String mac;

    WireMessage(Type type, int slot, long ts, String nonce, String ref,
            String signedPart, String mac) {
        this.type = type;
        this.slot = slot;
        this.ts = ts;
        this.nonce = nonce;
        this.ref = ref;
        this.signedPart = signedPart;
        this.mac = mac;
    }
}
