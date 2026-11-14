package dev.tvcallalert.core;

import static dev.tvcallalert.core.TestSupport.T;
import static dev.tvcallalert.core.TestSupport.alert;
import static dev.tvcallalert.core.TestSupport.cancel;
import static dev.tvcallalert.core.TestSupport.nonce;
import static dev.tvcallalert.core.TestSupport.ping;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class AlertPipelineTest {
    private TestSupport.FakeStore store;
    private AlertPipeline pipeline;
    private SlotKeys k1;
    private SlotKeys k2;

    @Before
    public void setUp() {
        store = new TestSupport.FakeStore();
        k1 = Kdf.derive(TestSupport.seed(0));
        k2 = Kdf.derive(TestSupport.seed(100));
        store.keys[1] = k1;
        store.keys[2] = k2;
        pipeline = new AlertPipeline(store);
    }

    private static void assertAccepted(Decision.Kind kind, int slot, String nonce, Decision d) {
        assertNull("rejection", d.rejection);
        assertEquals(kind, d.kind);
        assertTrue(d.authenticated);
        assertEquals(slot, d.slot);
        assertEquals(nonce, d.nonce);
    }

    private static void assertUnauthenticated(Rejection r, Decision d) {
        assertEquals(r, d.rejection);
        assertEquals(Decision.Kind.DROP, d.kind);
        assertFalse(d.authenticated);
        assertEquals(0, d.slot);
        assertNull(d.nonce);
        assertNull(d.ref);
    }

    private static void assertAuthenticatedDrop(Rejection r, int slot, String nonce, Decision d) {
        assertEquals(r, d.rejection);
        assertEquals(Decision.Kind.DROP, d.kind);
        assertTrue(d.authenticated);
        assertEquals(slot, d.slot);
        assertEquals(nonce, d.nonce);
    }

    private static String forgeMac(String line) {
        int cut = line.lastIndexOf('|');
        char[] mac = line.substring(cut + 1).toCharArray();
        mac[10] = mac[10] == 'A' ? 'B' : 'A';
        return line.substring(0, cut + 1) + new String(mac);
    }

    private static String macOf(String line) {
        return line.substring(line.lastIndexOf('|') + 1);
    }

    // ---- happy paths

    @Test
    public void validAlert_yieldsShowAndAdvancesHighWater() {
        Decision d = pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1), d);
        assertNull(d.ref);
        assertEquals(T, store.highWater[1]);
        assertEquals(0, store.highWater[2]);
    }

    @Test
    public void validPing_yieldsPong() {
        Decision d = pipeline.process(k1.topicDown, ping(k1, 1, T, nonce(1)), T);
        assertAccepted(Decision.Kind.PONG, 1, nonce(1), d);
        assertNull(d.ref);
    }

    @Test
    public void validCancel_yieldsCancelWithRef() {
        Decision d = pipeline.process(k1.topicDown, cancel(k1, 1, T, nonce(2), nonce(1)), T);
        assertAccepted(Decision.Kind.CANCEL, 1, nonce(2), d);
        assertEquals(nonce(1), d.ref);
    }

    @Test
    public void secondSlot_acceptedOnItsOwnTopic() {
        Decision d = pipeline.process(k2.topicDown, alert(k2, 2, T, nonce(1)), T);
        assertAccepted(Decision.Kind.SHOW, 2, nonce(1), d);
        assertEquals(T, store.highWater[2]);
        assertEquals(0, store.highWater[1]);
    }

    // ---- parse failures

    @Test
    public void malformedInput_droppedUnauthenticated() {
        assertUnauthenticated(Rejection.BAD_FORMAT, pipeline.process(k1.topicDown, null, T));
        assertUnauthenticated(Rejection.BAD_FORMAT, pipeline.process(k1.topicDown, "", T));
        assertUnauthenticated(Rejection.BAD_FORMAT, pipeline.process(k1.topicDown, "hello", T));
        assertUnauthenticated(Rejection.TOO_LARGE,
                pipeline.process(k1.topicDown, TestSupport.repeat('x', 201), T));
        assertUnauthenticated(Rejection.BAD_CHARSET,
                pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)) + "\n", T));
        assertEquals(0, store.setCalls);
    }

    // ---- check order

    @Test
    public void malformedOnUnknownTopic_reportsParseErrorFirst() {
        assertUnauthenticated(Rejection.BAD_FORMAT, pipeline.process("rtnope", "garbage", T));
    }

    @Test
    public void unknownTopicWithBadSignature_reportsUnknownTopicFirst() {
        String forged = forgeMac(alert(k1, 1, T, nonce(1)));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC, pipeline.process("rtnope", forged, T));
    }

    @Test
    public void slotMismatchWithBadSignature_reportsSlotMismatchFirst() {
        String forged = forgeMac(alert(k2, 2, T, nonce(1)));
        assertUnauthenticated(Rejection.SLOT_MISMATCH, pipeline.process(k1.topicDown, forged, T));
    }

    @Test
    public void badSignatureAndStale_reportsBadSignatureFirst() {
        String forged = forgeMac(alert(k1, 1, T - 5000, nonce(1)));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown, forged, T));
    }

    @Test
    public void staleAndReplayed_reportsStaleFirst() {
        String line = alert(k1, 1, T, nonce(1));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1), pipeline.process(k1.topicDown, line, T));
        assertAuthenticatedDrop(Rejection.STALE, 1, nonce(1),
                pipeline.process(k1.topicDown, line, T + 61));
    }

    @Test
    public void replayedAndBelowHighWater_reportsReplayedFirst() {
        String a = alert(k1, 1, T, nonce(1));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1), pipeline.process(k1.topicDown, a, T));
        assertAccepted(Decision.Kind.CANCEL, 1, nonce(2), pipeline.process(k1.topicDown,
                cancel(k1, 1, T + 10, nonce(2), nonce(9)), T + 10));
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(1),
                pipeline.process(k1.topicDown, a, T + 10));
    }

    @Test
    public void replayedWithinCooldown_reportsReplayedNotRateLimited() {
        String a = alert(k1, 1, T, nonce(1));
        pipeline.process(k1.topicDown, a, T);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(1),
                pipeline.process(k1.topicDown, a, T + 1));
    }

    @Test
    public void belowHighWaterWithinCooldown_reportsReplayedNotRateLimited() {
        pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(2),
                pipeline.process(k1.topicDown, alert(k1, 1, T - 1, nonce(2)), T + 1));
    }

    // ---- topic and slot binding

    @Test
    public void unknownTopic_dropped() {
        String line = alert(k1, 1, T, nonce(1));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC, pipeline.process("rtunknown", line, T));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC, pipeline.process(null, line, T));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC, pipeline.process("", line, T));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC, pipeline.process(k1.topicAck, line, T));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC,
                pipeline.process(k1.topicDown + "x", line, T));
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC,
                pipeline.process(k1.topicDown.toUpperCase(), line, T));
        assertEquals(0, store.setCalls);
    }

    @Test
    public void noSlotsPaired_everyTopicUnknown() {
        AlertPipeline empty = new AlertPipeline(new TestSupport.FakeStore());
        assertUnauthenticated(Rejection.UNKNOWN_TOPIC,
                empty.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T));
    }

    @Test
    public void validSlot2MessageOnSlot1Topic_isSlotMismatch() {
        String line = alert(k2, 2, T, nonce(1));
        assertUnauthenticated(Rejection.SLOT_MISMATCH, pipeline.process(k1.topicDown, line, T));
        assertEquals(0, store.setCalls);
        // The same line is still good on its own topic: the misrouted copy stored nothing.
        assertAccepted(Decision.Kind.SHOW, 2, nonce(1), pipeline.process(k2.topicDown, line, T));
    }

    @Test
    public void slot2KeyClaimingSlot1OnSlot1Topic_isBadSignature() {
        String line = alert(k2, 1, T, nonce(1));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown, line, T));
    }

    @Test
    public void slot1KeyClaimingSlot2OnSlot2Topic_isBadSignature() {
        String line = alert(k1, 2, T, nonce(1));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k2.topicDown, line, T));
    }

    @Test
    public void unpairedSlotFieldOnPairedTopic_isSlotMismatch() {
        String line = alert(k1, 3, T, nonce(1));
        assertUnauthenticated(Rejection.SLOT_MISMATCH, pipeline.process(k1.topicDown, line, T));
    }

    // ---- signatures

    @Test
    public void badSignature_neverAuthenticated() {
        String good = alert(k1, 1, T, nonce(1));
        assertUnauthenticated(Rejection.BAD_SIGNATURE,
                pipeline.process(k1.topicDown, forgeMac(good), T));
        String signed = TestSupport.signedPart("alert", 1, T, nonce(1), "-");
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                signed + "|" + Kdf.sign(k1.ackKey(), signed), T));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                signed + "|" + TestSupport.MAC_A, T));
        assertEquals(0, store.setCalls);
    }

    @Test
    public void macFromDifferentPayload_isBadSignature() {
        String mac = macOf(ping(k1, 1, T, nonce(1)));
        // A genuine ping mac reused on an alert, on another nonce and on another ts.
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                TestSupport.signedPart("alert", 1, T, nonce(1), "-") + "|" + mac, T));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                TestSupport.signedPart("ping", 1, T, nonce(2), "-") + "|" + mac, T));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                TestSupport.signedPart("ping", 1, T + 1, nonce(1), "-") + "|" + mac, T));
        String cmac = macOf(cancel(k1, 1, T, nonce(3), nonce(1)));
        assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                TestSupport.signedPart("cancel", 1, T, nonce(3), nonce(2)) + "|" + cmac, T));
    }

    @Test
    public void forgedMessages_doNotChangeAnyState() {
        // More forgeries than the replay cache or any rate limit holds, each well formed and
        // future dated, some reusing the nonces of the genuine messages that follow.
        for (int i = 0; i < 500; i++) {
            assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                    forgeMac(alert(k1, 1, T + 20, nonce(i))), T));
            assertUnauthenticated(Rejection.BAD_SIGNATURE, pipeline.process(k1.topicDown,
                    forgeMac(ping(k1, 1, T + 20, nonce(1000 + i))), T));
        }
        assertEquals(0, store.setCalls);
        assertEquals(0, store.highWater[1]);
        // Not replayed, not below a high-water mark, not rate limited.
        assertAccepted(Decision.Kind.SHOW, 1, nonce(0),
                pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(0)), T));
        assertAccepted(Decision.Kind.PONG, 1, nonce(1000),
                pipeline.process(k1.topicDown, ping(k1, 1, T + 1, nonce(1000)), T + 1));
    }

    @Test
    public void misroutedMessages_doNotChangeAnyState() {
        for (int i = 0; i < 200; i++) {
            pipeline.process("rtunknown", alert(k1, 1, T + 20, nonce(i)), T);
            pipeline.process(k1.topicDown, alert(k2, 2, T + 20, nonce(i)), T);
        }
        assertEquals(0, store.setCalls);
        assertAccepted(Decision.Kind.SHOW, 1, nonce(0),
                pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(0)), T));
        assertAccepted(Decision.Kind.SHOW, 2, nonce(1),
                pipeline.process(k2.topicDown, alert(k2, 2, T, nonce(1)), T));
    }

    @Test
    public void sameNonceOnAnotherSlot_treatedAsReplay() {
        // The nonce cache is shared by all slots. Documented here so a change is deliberate;
        // 16 random bytes never collide between two honest phones.
        pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        assertAuthenticatedDrop(Rejection.REPLAYED, 2, nonce(1),
                pipeline.process(k2.topicDown, alert(k2, 2, T, nonce(1)), T));
    }

    // ---- freshness

    @Test
    public void tsExactly30SecondsAhead_accepted() {
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 30, nonce(1)), T));
    }

    @Test
    public void ts31SecondsAhead_rejectedAsFuture() {
        assertAuthenticatedDrop(Rejection.FUTURE, 1, nonce(1),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 31, nonce(1)), T));
        assertEquals(0, store.setCalls);
    }

    @Test
    public void tsExactly60SecondsOld_accepted() {
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1),
                pipeline.process(k1.topicDown, alert(k1, 1, T - 60, nonce(1)), T));
    }

    @Test
    public void ts61SecondsOld_rejectedAsStale() {
        assertAuthenticatedDrop(Rejection.STALE, 1, nonce(1),
                pipeline.process(k1.topicDown, alert(k1, 1, T - 61, nonce(1)), T));
        assertEquals(0, store.setCalls);
    }

    @Test
    public void staleOrFutureMessage_doesNotConsumeNonceOrRateLimit() {
        String line = alert(k1, 1, T, nonce(1));
        assertAuthenticatedDrop(Rejection.FUTURE, 1, nonce(1),
                pipeline.process(k1.topicDown, line, T - 31));
        assertAuthenticatedDrop(Rejection.STALE, 1, nonce(1),
                pipeline.process(k1.topicDown, line, T + 61));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1), pipeline.process(k1.topicDown, line, T));
    }

    @Test
    public void unsyncedRelayClock_neverAccepts() {
        Decision d = pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), -1);
        assertEquals(Decision.Kind.DROP, d.kind);
        assertEquals(0, store.setCalls);
    }

    // ---- replay

    @Test
    public void sameLineTwice_secondIsReplayed() {
        String line = alert(k1, 1, T, nonce(1));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1), pipeline.process(k1.topicDown, line, T));
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(1),
                pipeline.process(k1.topicDown, line, T));
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(1),
                pipeline.process(k1.topicDown, line, T + 60));
        assertEquals(1, store.setCalls);
    }

    @Test
    public void replayedCancel_keepsRef() {
        String line = cancel(k1, 1, T, nonce(2), nonce(1));
        pipeline.process(k1.topicDown, line, T);
        Decision d = pipeline.process(k1.topicDown, line, T + 1);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(2), d);
        assertEquals(nonce(1), d.ref);
    }

    @Test
    public void replayAtAnyLaterTime_neverAcceptedAgain() {
        String line = alert(k1, 1, T + 30, nonce(1));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1), pipeline.process(k1.topicDown, line, T));
        for (long now = T + 1; now < T + 400; now++) {
            Decision d = pipeline.process(k1.topicDown, line, now);
            assertEquals("at +" + (now - T), Decision.Kind.DROP, d.kind);
        }
        assertEquals(1, store.setCalls);
    }

    @Test
    public void replayWithRelayClockSteppedBack_stillRejected() {
        String line = alert(k1, 1, T, nonce(1));
        pipeline.process(k1.topicDown, line, T + 20);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(1),
                pipeline.process(k1.topicDown, line, T - 20));
    }

    // ---- high-water mark

    @Test
    public void olderTsAfterNewer_rejectedAsReplayed() {
        pipeline.process(k1.topicDown, cancel(k1, 1, T + 10, nonce(1), nonce(9)), T + 10);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(2), pipeline.process(k1.topicDown,
                cancel(k1, 1, T + 9, nonce(2), nonce(9)), T + 10));
        assertEquals(T + 10, store.highWater[1]);
        assertEquals(1, store.setCalls);
    }

    @Test
    public void equalTsWithNewNonce_rejectedAsReplayed() {
        pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(2), pipeline.process(k1.topicDown,
                cancel(k1, 1, T, nonce(2), nonce(1)), T));
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(3),
                pipeline.process(k1.topicDown, ping(k1, 1, T, nonce(3)), T));
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(4),
                pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(4)), T + 15));
        assertEquals(1, store.setCalls);
    }

    @Test
    public void tsOneAboveHighWater_accepted() {
        pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        assertAccepted(Decision.Kind.CANCEL, 1, nonce(2), pipeline.process(k1.topicDown,
                cancel(k1, 1, T + 1, nonce(2), nonce(1)), T));
        assertAccepted(Decision.Kind.PONG, 1, nonce(3),
                pipeline.process(k1.topicDown, ping(k1, 1, T + 2, nonce(3)), T));
        assertEquals(T + 2, store.highWater[1]);
    }

    @Test
    public void rejectedByHighWater_doesNotBurnNonceOrRateLimit() {
        pipeline.process(k1.topicDown, cancel(k1, 1, T, nonce(1), nonce(9)), T);
        for (int i = 0; i < 100; i++) {
            assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(100 + i), pipeline.process(
                    k1.topicDown, alert(k1, 1, T, nonce(100 + i)), T));
        }
        assertAccepted(Decision.Kind.SHOW, 1, nonce(100),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 1, nonce(100)), T + 1));
    }

    @Test
    public void acceptedLineAfterRestart_rejectedAsReplayed() {
        String alertLine = alert(k1, 1, T, nonce(1));
        String cancelLine = cancel(k1, 1, T + 3, nonce(2), nonce(1));
        String pingLine = ping(k2, 2, T, nonce(3));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1),
                pipeline.process(k1.topicDown, alertLine, T));
        assertAccepted(Decision.Kind.CANCEL, 1, nonce(2),
                pipeline.process(k1.topicDown, cancelLine, T + 3));
        assertAccepted(Decision.Kind.PONG, 2, nonce(3),
                pipeline.process(k2.topicDown, pingLine, T));
        int writes = store.setCalls;
        // The app restarts: the nonce cache and the rate limiters are gone, the store is not.
        // The relay then redelivers its cached copies while they are still fresh.
        for (long now = T + 3; now <= T + 60; now += 19) {
            AlertPipeline restarted = new AlertPipeline(store);
            assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(1),
                    restarted.process(k1.topicDown, alertLine, now));
            Decision d = restarted.process(k1.topicDown, cancelLine, now);
            assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(2), d);
            assertEquals(nonce(1), d.ref);
            assertAuthenticatedDrop(Rejection.REPLAYED, 2, nonce(3),
                    restarted.process(k2.topicDown, pingLine, now));
        }
        assertEquals(writes, store.setCalls);
        // A genuinely new message still gets through after the restart.
        assertAccepted(Decision.Kind.SHOW, 1, nonce(4), new AlertPipeline(store).process(
                k1.topicDown, alert(k1, 1, T + 20, nonce(4)), T + 20));
    }

    @Test
    public void highWater_isPerSlot() {
        pipeline.process(k1.topicDown, alert(k1, 1, T + 20, nonce(1)), T);
        assertAccepted(Decision.Kind.SHOW, 2, nonce(2),
                pipeline.process(k2.topicDown, alert(k2, 2, T - 20, nonce(2)), T));
    }

    @Test
    public void olderTsWithNewNonceAfterRestart_rejectedAsReplayed() {
        pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        AlertPipeline restarted = new AlertPipeline(store);
        assertAuthenticatedDrop(Rejection.REPLAYED, 1, nonce(2), restarted.process(k1.topicDown,
                alert(k1, 1, T - 1, nonce(2)), T));
    }

    @Test
    public void highWater_neverMovesBackwards() {
        long last = 0;
        long[] tsOffsets = {0, 5, 3, 5, 20, -10, 19, 30, 29, 30};
        for (int i = 0; i < tsOffsets.length; i++) {
            pipeline.process(k1.topicDown,
                    cancel(k1, 1, T + tsOffsets[i], nonce(i), nonce(99)), T);
            assertTrue(store.highWater[1] >= last);
            last = store.highWater[1];
        }
        assertEquals(T + 30, last);
        assertEquals(4, store.setCalls);
    }

    // ---- rate limits

    @Test
    public void alertCooldown_tenSeconds() {
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1),
                pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T));
        assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(2),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 9, nonce(2)), T + 9));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(3),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 10, nonce(3)), T + 10));
    }

    @Test
    public void rateLimitedAlert_doesNotAdvanceHighWaterOrBurnNonceOrExtendCooldown() {
        pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T);
        String limited = alert(k1, 1, T + 5, nonce(2));
        for (long now = T + 5; now < T + 10; now++) {
            assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(2),
                    pipeline.process(k1.topicDown, limited, now));
        }
        assertEquals(T, store.highWater[1]);
        assertEquals(1, store.setCalls);
        // A message older than the limited one, but newer than the last accepted, is fine.
        assertAccepted(Decision.Kind.CANCEL, 1, nonce(3), pipeline.process(k1.topicDown,
                cancel(k1, 1, T + 2, nonce(3), nonce(1)), T + 9));
        // The retry of the limited line goes through once the cooldown from the accepted
        // alert has passed: its nonce was not recorded and the failed tries did not count.
        assertAccepted(Decision.Kind.SHOW, 1, nonce(2),
                pipeline.process(k1.topicDown, limited, T + 10));
    }

    @Test
    public void alertWindow_threePerFiveMinutes() {
        long[] ok = {0, 10, 20};
        for (int i = 0; i < ok.length; i++) {
            assertAccepted(Decision.Kind.SHOW, 1, nonce(i), pipeline.process(k1.topicDown,
                    alert(k1, 1, T + ok[i], nonce(i)), T + ok[i]));
        }
        int n = 10;
        for (long off = 30; off < 300; off += 10) {
            assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(n), pipeline.process(
                    k1.topicDown, alert(k1, 1, T + off, nonce(n)), T + off));
            n++;
        }
        assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(n), pipeline.process(
                k1.topicDown, alert(k1, 1, T + 299, nonce(n)), T + 299));
        // The first alert leaves the window at exactly 300 s; the limited tries never counted.
        assertAccepted(Decision.Kind.SHOW, 1, nonce(500), pipeline.process(k1.topicDown,
                alert(k1, 1, T + 300, nonce(500)), T + 300));
        assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(501), pipeline.process(
                k1.topicDown, alert(k1, 1, T + 309, nonce(501)), T + 309));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(502), pipeline.process(k1.topicDown,
                alert(k1, 1, T + 310, nonce(502)), T + 310));
    }

    @Test
    public void alertDailyCap_thirtyPerDay() {
        for (int i = 0; i < 30; i++) {
            long now = T + i * 100L;
            assertAccepted(Decision.Kind.SHOW, 1, nonce(i),
                    pipeline.process(k1.topicDown, alert(k1, 1, now, nonce(i)), now));
        }
        long[] limitedAt = {T + 3000, T + 40000, T + 86399};
        for (int i = 0; i < limitedAt.length; i++) {
            assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(100 + i), pipeline.process(
                    k1.topicDown, alert(k1, 1, limitedAt[i], nonce(100 + i)), limitedAt[i]));
        }
        long next = T + 86400;
        assertAccepted(Decision.Kind.SHOW, 1, nonce(200),
                pipeline.process(k1.topicDown, alert(k1, 1, next, nonce(200)), next));
        assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(201), pipeline.process(
                k1.topicDown, alert(k1, 1, next + 50, nonce(201)), next + 50));
    }

    @Test
    public void alertLimits_arePerSlot() {
        assertAccepted(Decision.Kind.SHOW, 1, nonce(1),
                pipeline.process(k1.topicDown, alert(k1, 1, T, nonce(1)), T));
        assertAccepted(Decision.Kind.SHOW, 2, nonce(2),
                pipeline.process(k2.topicDown, alert(k2, 2, T, nonce(2)), T));
    }

    @Test
    public void cancel_isNotRateLimitedAndCarriesRef() {
        pipeline.process(k1.topicDown, alert(k1, 1, T - 30, nonce(0)), T);
        for (int i = 1; i <= 40; i++) {
            Decision d = pipeline.process(k1.topicDown,
                    cancel(k1, 1, T - 30 + i, nonce(i), nonce(0)), T);
            assertAccepted(Decision.Kind.CANCEL, 1, nonce(i), d);
            assertEquals(nonce(0), d.ref);
        }
    }

    @Test
    public void cancel_doesNotConsumeAlertLimit() {
        for (int i = 1; i <= 10; i++) {
            pipeline.process(k1.topicDown, cancel(k1, 1, T + i, nonce(i), nonce(0)), T);
        }
        assertAccepted(Decision.Kind.SHOW, 1, nonce(50),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 11, nonce(50)), T));
    }

    @Test
    public void pingCooldown_twoSeconds() {
        assertAccepted(Decision.Kind.PONG, 1, nonce(1),
                pipeline.process(k1.topicDown, ping(k1, 1, T, nonce(1)), T));
        assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(2),
                pipeline.process(k1.topicDown, ping(k1, 1, T + 1, nonce(2)), T + 1));
        assertAccepted(Decision.Kind.PONG, 1, nonce(3),
                pipeline.process(k1.topicDown, ping(k1, 1, T + 2, nonce(3)), T + 2));
    }

    @Test
    public void pingAndAlert_useSeparateLimits() {
        assertAccepted(Decision.Kind.PONG, 1, nonce(1),
                pipeline.process(k1.topicDown, ping(k1, 1, T, nonce(1)), T));
        assertAccepted(Decision.Kind.SHOW, 1, nonce(2),
                pipeline.process(k1.topicDown, alert(k1, 1, T + 1, nonce(2)), T + 1));
        assertAccepted(Decision.Kind.PONG, 1, nonce(3),
                pipeline.process(k1.topicDown, ping(k1, 1, T + 2, nonce(3)), T + 2));
    }

    // ---- replay cache capacity

    @Test
    public void replayCacheFull_failsClosedWithAuthenticatedDrop() {
        // Accepted messages need strictly increasing ts, so spread 64 of them over the
        // freshness window of a single instant.
        for (int i = 0; i < Limits.NONCE_CACHE_SIZE; i++) {
            assertAccepted(Decision.Kind.CANCEL, 1, nonce(i), pipeline.process(k1.topicDown,
                    cancel(k1, 1, T - 33 + i, nonce(i), nonce(999)), T));
        }
        assertAuthenticatedDrop(Rejection.RATE_LIMITED, 1, nonce(100), pipeline.process(
                k1.topicDown, cancel(k1, 1, T + 31, nonce(100), nonce(999)), T + 1));
        assertEquals(Limits.NONCE_CACHE_SIZE, store.setCalls);
        // Entries expire after the TTL and the cache accepts again.
        assertAccepted(Decision.Kind.CANCEL, 1, nonce(101), pipeline.process(k1.topicDown,
                cancel(k1, 1, T + 121, nonce(101), nonce(999)), T + 121));
    }

    @Test
    public void alertDroppedBecauseCacheFull_doesNotConsumeAlertRateLimit() {
        for (int i = 0; i < Limits.NONCE_CACHE_SIZE; i++) {
            assertAccepted(Decision.Kind.CANCEL, 1, nonce(i), pipeline.process(k1.topicDown,
                    cancel(k1, 1, T - 33 + i, nonce(i), nonce(999)), T));
        }
        // Three alerts on the other slot are dropped only because the cache is full.
        for (int i = 0; i < 3; i++) {
            long now = T + i * 10L;
            assertAuthenticatedDrop(Rejection.RATE_LIMITED, 2, nonce(200 + i), pipeline.process(
                    k2.topicDown, alert(k2, 2, now, nonce(200 + i)), now));
        }
        assertEquals(0, store.highWater[2]);
        // No alert was ever shown for slot 2, so its 3 per 5 minutes budget must be intact.
        long later = T + 121;
        assertAccepted(Decision.Kind.SHOW, 2, nonce(300),
                pipeline.process(k2.topicDown, alert(k2, 2, later, nonce(300)), later));
    }
}
