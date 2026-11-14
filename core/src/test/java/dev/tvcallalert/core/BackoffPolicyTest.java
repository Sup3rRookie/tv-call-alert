package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class BackoffPolicyTest {
    /** Random whose nextDouble is fixed, to hit the exact ends of the jitter range. */
    private static final class FixedRandom extends Random {
        private static final long serialVersionUID = 1L;
        private final double value;

        FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }

    private static long ceilingFor(int failures, long cap) {
        return Math.min(cap, 1000L << Math.min(failures, 20));
    }

    private static void assertInRange(String what, long delay, long ceiling) {
        assertTrue(what + " delay " + delay + " below floor " + ceiling / 4, delay >= ceiling / 4);
        assertTrue(what + " delay " + delay + " above ceiling " + ceiling, delay <= ceiling);
    }

    @Test
    public void firstDelay_betweenQuarterSecondAndOneSecond() {
        for (int seed = 0; seed < 200; seed++) {
            long d = new BackoffPolicy(new Random(seed)).nextDelayMillis(false);
            assertInRange("seed " + seed, d, 1000);
        }
    }

    @Test
    public void everyDelay_withinQuarterCeilingAndCeiling() {
        for (int seed = 0; seed < 50; seed++) {
            BackoffPolicy p = new BackoffPolicy(new Random(seed));
            for (int n = 0; n < 40; n++) {
                assertInRange("seed " + seed + " n " + n, p.nextDelayMillis(false),
                        ceilingFor(n, BackoffPolicy.CAP_MILLIS));
            }
        }
    }

    @Test
    public void minimumJitter_isExactlyQuarterOfCeiling() {
        BackoffPolicy p = new BackoffPolicy(new FixedRandom(0.0));
        long[] expected = {250, 500, 1000, 2000, 4000, 8000, 15000, 15000, 15000};
        for (int i = 0; i < expected.length; i++) {
            assertEquals("attempt " + i, expected[i], p.nextDelayMillis(false));
        }
    }

    @Test
    public void maximumJitter_growsAndStaysAtOrBelowCap() {
        BackoffPolicy p = new BackoffPolicy(new FixedRandom(Math.nextDown(1.0)));
        long previous = 0;
        for (int i = 0; i < 30; i++) {
            long d = p.nextDelayMillis(false);
            assertTrue("non-decreasing at " + i, d >= previous);
            assertTrue(d <= BackoffPolicy.CAP_MILLIS);
            previous = d;
        }
        assertTrue("reaches near the cap, got " + previous, previous > 59_000);
    }

    @Test
    public void normalFailures_cappedAt60Seconds() {
        BackoffPolicy p = new BackoffPolicy(new Random(7));
        for (int i = 0; i < 500; i++) {
            long d = p.nextDelayMillis(false);
            assertTrue(d <= 60_000);
            if (i >= 6) {
                assertTrue("floor at cap, got " + d, d >= 15_000);
            }
        }
    }

    @Test
    public void serverPushback_cappedAt300Seconds() {
        BackoffPolicy p = new BackoffPolicy(new Random(7));
        long max = 0;
        for (int i = 0; i < 500; i++) {
            long d = p.nextDelayMillis(true);
            assertInRange("n " + i, d, ceilingFor(i, BackoffPolicy.SERVER_ERROR_CAP_MILLIS));
            assertTrue(d <= 300_000);
            if (i >= 9) {
                assertTrue("floor at server cap, got " + d, d >= 75_000);
            }
            max = Math.max(max, d);
        }
        assertTrue("server cap is actually used, max " + max, max > 60_000);
    }

    @Test
    public void normalFailureAfterServerPushback_returnsToNormalCap() {
        BackoffPolicy p = new BackoffPolicy(new Random(3));
        for (int i = 0; i < 20; i++) {
            p.nextDelayMillis(true);
        }
        assertTrue(p.nextDelayMillis(false) <= 60_000);
    }

    @Test
    public void healthyConnection_resetsBackoff() {
        BackoffPolicy p = new BackoffPolicy(new Random(5));
        for (int i = 0; i < 12; i++) {
            p.nextDelayMillis(false);
        }
        p.connectionEnded(BackoffPolicy.HEALTHY_MILLIS);
        assertInRange("after healthy", p.nextDelayMillis(false), 1000);
    }

    @Test
    public void shortConnection_doesNotResetBackoff() {
        BackoffPolicy p = new BackoffPolicy(new Random(5));
        for (int i = 0; i < 12; i++) {
            p.nextDelayMillis(false);
        }
        p.connectionEnded(BackoffPolicy.HEALTHY_MILLIS - 1);
        p.connectionEnded(0);
        p.connectionEnded(-1);
        assertTrue(p.nextDelayMillis(false) >= 15_000);
    }

    @Test
    public void reset_restartsFromBase() {
        BackoffPolicy p = new BackoffPolicy(new Random(5));
        for (int i = 0; i < 12; i++) {
            p.nextDelayMillis(true);
        }
        p.reset();
        assertInRange("after reset", p.nextDelayMillis(true), 1000);
    }

    @Test
    public void thousandsOfFailures_neverOverflowOrLeaveBounds() {
        BackoffPolicy p = new BackoffPolicy(new Random(11));
        for (int i = 0; i < 100_000; i++) {
            boolean server = i % 3 == 0;
            long d = p.nextDelayMillis(server);
            long cap = server ? 300_000 : 60_000;
            assertTrue("i=" + i + " d=" + d, d >= 250 && d <= cap);
            if (i > 20) {
                assertTrue("i=" + i + " d=" + d, d >= cap / 4);
            }
        }
    }
}
