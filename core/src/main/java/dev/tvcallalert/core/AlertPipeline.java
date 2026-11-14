package dev.tvcallalert.core;

/**
 * Decides what to do with a raw relay message. Checks run in a fixed order and nothing is
 * stored or acknowledged before the signature has been verified.
 */
public final class AlertPipeline {
    private final SlotStore store;
    private final ReplayGuard replay = new ReplayGuard();
    private final RateLimiter[] alertLimiters = new RateLimiter[Limits.MAX_SLOTS + 1];
    private final RateLimiter[] otherLimiters = new RateLimiter[Limits.MAX_SLOTS + 1];

    public AlertPipeline(SlotStore store) {
        this.store = store;
        for (int i = 1; i <= Limits.MAX_SLOTS; i++) {
            alertLimiters[i] = RateLimiter.forAlerts();
            otherLimiters[i] = RateLimiter.forPings();
        }
    }

    /**
     * Runs every check on one message.
     *
     * @param topic the relay topic the message arrived on
     * @param raw the message body
     * @param relayNowSeconds current relay time
     */
    public synchronized Decision process(String topic, String raw, long relayNowSeconds) {
        MessageParser.Result parsed = MessageParser.parse(raw);
        if (parsed.message == null) {
            return Decision.drop(parsed.rejection);
        }
        WireMessage m = parsed.message;
        int topicSlot = slotForTopic(topic);
        if (topicSlot == 0) {
            return Decision.drop(Rejection.UNKNOWN_TOPIC);
        }
        if (topicSlot != m.slot) {
            return Decision.drop(Rejection.SLOT_MISMATCH);
        }
        SlotKeys keys = store.keys(m.slot);
        if (keys == null || !Kdf.verify(keys.authKey(), m.signedPart, m.mac)) {
            return Decision.drop(Rejection.BAD_SIGNATURE);
        }
        long age = relayNowSeconds - m.ts;
        if (age > Limits.MAX_AGE_SECONDS) {
            return Decision.dropAuthenticated(Rejection.STALE, m);
        }
        if (age < -Limits.MAX_FUTURE_SECONDS) {
            return Decision.dropAuthenticated(Rejection.FUTURE, m);
        }
        if (replay.isReplay(m.nonce, relayNowSeconds)) {
            return Decision.dropAuthenticated(Rejection.REPLAYED, m);
        }
        // The high-water mark is persisted, so this also stops the relay's cached copy of an
        // already accepted message from being accepted again after the app restarts.
        if (m.ts <= store.highWater(m.slot)) {
            return Decision.dropAuthenticated(Rejection.REPLAYED, m);
        }
        // Checked before the rate limiter so a message dropped only because the nonce cache
        // is full does not use up the slot's rate limit budget.
        if (replay.isFull(relayNowSeconds)) {
            return Decision.dropAuthenticated(Rejection.RATE_LIMITED, m);
        }
        // A cancel is never rate limited: it can only remove a card, not show one.
        if (m.type != WireMessage.Type.CANCEL) {
            RateLimiter limiter = m.type == WireMessage.Type.ALERT
                    ? alertLimiters[m.slot] : otherLimiters[m.slot];
            if (!limiter.tryAcquire(relayNowSeconds)) {
                return Decision.dropAuthenticated(Rejection.RATE_LIMITED, m);
            }
        }
        if (!replay.record(m.nonce, relayNowSeconds)) {
            return Decision.dropAuthenticated(Rejection.RATE_LIMITED, m);
        }
        store.setHighWater(m.slot, m.ts);
        switch (m.type) {
            case ALERT:
                return Decision.accept(Decision.Kind.SHOW, m);
            case CANCEL:
                return Decision.accept(Decision.Kind.CANCEL, m);
            default:
                return Decision.accept(Decision.Kind.PONG, m);
        }
    }

    private int slotForTopic(String topic) {
        if (topic == null) {
            return 0;
        }
        for (int i = 1; i <= Limits.MAX_SLOTS; i++) {
            SlotKeys k = store.keys(i);
            if (k != null && k.topicDown.equals(topic)) {
                return i;
            }
        }
        return 0;
    }
}
