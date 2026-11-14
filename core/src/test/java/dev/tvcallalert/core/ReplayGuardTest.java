package dev.tvcallalert.core;

import static dev.tvcallalert.core.TestSupport.nonce;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReplayGuardTest {
    @Test
    public void unseenNonce_isNotReplay() {
        assertFalse(new ReplayGuard().isReplay(nonce(1), 1000));
    }

    @Test
    public void recordedNonce_isReplayImmediately() {
        ReplayGuard g = new ReplayGuard();
        assertTrue(g.record(nonce(1), 1000));
        assertTrue(g.isReplay(nonce(1), 1000));
        assertFalse(g.isReplay(nonce(2), 1000));
    }

    @Test
    public void atExactlyTtl_stillReplay() {
        ReplayGuard g = new ReplayGuard();
        g.record(nonce(1), 1000);
        assertTrue(g.isReplay(nonce(1), 1000 + Limits.NONCE_TTL_SECONDS));
    }

    @Test
    public void oneSecondPastTtl_expired() {
        ReplayGuard g = new ReplayGuard();
        g.record(nonce(1), 1000);
        assertFalse(g.isReplay(nonce(1), 1000 + Limits.NONCE_TTL_SECONDS + 1));
    }

    @Test
    public void fullOfLiveEntries_recordReturnsFalse() {
        ReplayGuard g = new ReplayGuard();
        for (int i = 0; i < Limits.NONCE_CACHE_SIZE; i++) {
            assertTrue("entry " + i, g.record(nonce(i), 1000 + i % 2));
        }
        assertFalse(g.record(nonce(500), 1001));
        assertFalse(g.record(nonce(500), 1000 + Limits.NONCE_TTL_SECONDS));
        assertFalse("rejected nonce must not be remembered", g.isReplay(nonce(500), 1001));
        // Nothing was evicted to make room.
        for (int i = 0; i < Limits.NONCE_CACHE_SIZE; i++) {
            assertTrue(g.isReplay(nonce(i), 1001));
        }
    }

    @Test
    public void fullCache_acceptsAgainAfterExpiry() {
        ReplayGuard g = new ReplayGuard();
        for (int i = 0; i < Limits.NONCE_CACHE_SIZE - 1; i++) {
            g.record(nonce(i), 1000);
        }
        g.record(nonce(900), 1100);
        assertFalse(g.record(nonce(500), 1120));
        assertTrue(g.record(nonce(500), 1121));
        assertTrue(g.isReplay(nonce(900), 1121));
        assertFalse(g.isReplay(nonce(0), 1121));
    }

    @Test
    public void clockSteppingBackwards_doesNotReopenReplay() {
        ReplayGuard g = new ReplayGuard();
        g.record(nonce(1), 1000);
        assertTrue(g.isReplay(nonce(1), 500));
        assertTrue(g.isReplay(nonce(1), 0));
        assertTrue(g.isReplay(nonce(1), -5000));
        assertTrue(g.isReplay(nonce(1), 1000));
        assertTrue(g.isReplay(nonce(1), 1120));
        assertFalse(g.isReplay(nonce(1), 1121));
    }

    @Test
    public void clockSteppingBackwardsWhileFull_doesNotFreeEntries() {
        ReplayGuard g = new ReplayGuard();
        for (int i = 0; i < Limits.NONCE_CACHE_SIZE; i++) {
            g.record(nonce(i), 1000);
        }
        assertFalse(g.record(nonce(500), 10));
        assertTrue(g.isReplay(nonce(0), 10));
    }
}
