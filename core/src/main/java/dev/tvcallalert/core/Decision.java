package dev.tvcallalert.core;

/** What the app should do with one incoming relay message. */
public final class Decision {
    /** The action to take. */
    public enum Kind { SHOW, CANCEL, PONG, DROP }

    public final Kind kind;
    /** Slot of the sender. 0 when the message never matched a slot. */
    public final int slot;
    /** Nonce of the message, null when it was never authenticated. */
    public final String nonce;
    /** For CANCEL, the alert nonce to dismiss. */
    public final String ref;
    public final Rejection rejection;
    /** True when the signature was valid, which is the only case an ack may be sent. */
    public final boolean authenticated;

    private Decision(Kind kind, int slot, String nonce, String ref, Rejection rejection,
            boolean authenticated) {
        this.kind = kind;
        this.slot = slot;
        this.nonce = nonce;
        this.ref = ref;
        this.rejection = rejection;
        this.authenticated = authenticated;
    }

    static Decision accept(Kind kind, WireMessage m) {
        return new Decision(kind, m.slot, m.nonce, m.ref, null, true);
    }

    static Decision drop(Rejection r) {
        return new Decision(Kind.DROP, 0, null, null, r, false);
    }

    static Decision dropAuthenticated(Rejection r, WireMessage m) {
        return new Decision(Kind.DROP, m.slot, m.nonce, m.ref, r, true);
    }
}
