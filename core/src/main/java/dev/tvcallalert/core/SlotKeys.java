package dev.tvcallalert.core;

/** Keys and relay topics derived from one phone's pairing seed. */
public final class SlotKeys {
    private final byte[] authKey;
    private final byte[] ackKey;
    public final String topicDown;
    public final String topicAck;

    SlotKeys(byte[] authKey, byte[] ackKey, String topicDown, String topicAck) {
        this.authKey = authKey;
        this.ackKey = ackKey;
        this.topicDown = topicDown;
        this.topicAck = topicAck;
    }

    byte[] authKey() {
        return authKey;
    }

    byte[] ackKey() {
        return ackKey;
    }
}
