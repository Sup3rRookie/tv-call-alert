package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RateLimiterTest {
    @Test
    public void firstEvent_allowed() {
        assertTrue(RateLimiter.forAlerts().tryAcquire(1000));
    }

    @Test
    public void cooldownBoundary_nineRejectedTenAllowed() {
        RateLimiter r = RateLimiter.forAlerts();
        assertTrue(r.tryAcquire(1000));
        assertFalse(r.tryAcquire(1000));
        assertFalse(r.tryAcquire(1009));
        assertTrue(r.tryAcquire(1010));
    }

    @Test
    public void rejectedAttempts_doNotExtendCooldown() {
        RateLimiter r = RateLimiter.forAlerts();
        assertTrue(r.tryAcquire(1000));
        for (long t = 1000; t < 1010; t++) {
            assertFalse(r.tryAcquire(t));
        }
        assertTrue(r.tryAcquire(1010));
    }

    @Test
    public void windowCap_fourthInWindowRejected() {
        RateLimiter r = RateLimiter.forAlerts();
        assertTrue(r.tryAcquire(1000));
        assertTrue(r.tryAcquire(1010));
        assertTrue(r.tryAcquire(1020));
        assertFalse(r.tryAcquire(1030));
        assertFalse(r.tryAcquire(1299));
    }

    @Test
    public void windowRefill_oldestLeavesAtExactlyWindow() {
        RateLimiter r = RateLimiter.forAlerts();
        assertTrue(r.tryAcquire(1000));
        assertTrue(r.tryAcquire(1010));
        assertTrue(r.tryAcquire(1020));
        assertFalse(r.tryAcquire(1299));
        assertTrue(r.tryAcquire(1300));
        assertFalse(r.tryAcquire(1309));
        assertTrue(r.tryAcquire(1310));
        assertTrue(r.tryAcquire(1320));
        assertFalse(r.tryAcquire(1330));
    }

    @Test
    public void clockSteppingBackwards_countsAsRecent() {
        RateLimiter r = RateLimiter.forAlerts();
        assertTrue(r.tryAcquire(1000));
        assertFalse(r.tryAcquire(900));
        assertFalse(r.tryAcquire(0));
        assertFalse(r.tryAcquire(-100000));
        assertFalse(r.tryAcquire(1009));
        assertTrue(r.tryAcquire(1010));
    }

    @Test
    public void clockSteppingBackwards_doesNotGrantExtraWindowBudget() {
        RateLimiter r = RateLimiter.forAlerts();
        assertTrue(r.tryAcquire(1000));
        assertTrue(r.tryAcquire(1010));
        assertTrue(r.tryAcquire(1020));
        for (long t = 0; t < 1300; t += 7) {
            assertFalse("t=" + t, r.tryAcquire(t));
        }
    }

    @Test
    public void dailyCap_thirtyFirstRejectedUntilOldestIsADayOld() {
        RateLimiter r = RateLimiter.forAlerts();
        for (int i = 0; i < 30; i++) {
            assertTrue("i=" + i, r.tryAcquire(1000 + i * 100L));
        }
        assertFalse(r.tryAcquire(1000 + 3000));
        assertFalse(r.tryAcquire(1000 + 50000));
        assertFalse(r.tryAcquire(1000 + Limits.DAY_SECONDS - 1));
        assertTrue(r.tryAcquire(1000 + Limits.DAY_SECONDS));
        // Only one slot was freed.
        assertFalse(r.tryAcquire(1000 + Limits.DAY_SECONDS + 50));
        assertTrue(r.tryAcquire(1000 + Limits.DAY_SECONDS + 100));
    }

    @Test
    public void dailyCap_holdsUnderConstantHammering() {
        RateLimiter r = RateLimiter.forAlerts();
        int accepted = 0;
        for (long t = 0; t < Limits.DAY_SECONDS; t++) {
            if (r.tryAcquire(t)) {
                accepted++;
            }
        }
        assertEquals(Limits.ALERTS_PER_DAY, accepted);
    }

    @Test
    public void customLimiter_dailyCapAlone() {
        RateLimiter r = new RateLimiter(0, 1000, 10, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(r.tryAcquire(100));
        }
        assertFalse(r.tryAcquire(100));
        assertFalse(r.tryAcquire(100 + Limits.DAY_SECONDS - 1));
        assertTrue(r.tryAcquire(100 + Limits.DAY_SECONDS));
    }

    @Test
    public void pingLimiter_twoSecondCooldown() {
        RateLimiter r = RateLimiter.forPings();
        assertTrue(r.tryAcquire(1000));
        assertFalse(r.tryAcquire(1001));
        assertTrue(r.tryAcquire(1002));
    }

    @Test
    public void independentInstances_doNotShareState() {
        RateLimiter a = RateLimiter.forAlerts();
        RateLimiter b = RateLimiter.forAlerts();
        assertTrue(a.tryAcquire(1000));
        assertTrue(b.tryAcquire(1000));
    }
}
