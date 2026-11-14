package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RelayClockTest {
    @Test
    public void unsynced_returnsMinusOne() {
        RelayClock c = new RelayClock();
        assertFalse(c.isSynced());
        assertEquals(-1, c.nowSeconds(0));
        assertEquals(-1, c.nowSeconds(123456789L));
        assertEquals(-1, c.nowSeconds(-5));
    }

    @Test
    public void synced_returnsServerTimeAtSyncInstant() {
        RelayClock c = new RelayClock();
        c.sync(1794700000L, 5000);
        assertTrue(c.isSynced());
        assertEquals(1794700000L, c.nowSeconds(5000));
    }

    @Test
    public void elapsedTime_advancesInWholeSeconds() {
        RelayClock c = new RelayClock();
        c.sync(1794700000L, 5000);
        assertEquals(1794700000L, c.nowSeconds(5999));
        assertEquals(1794700001L, c.nowSeconds(6000));
        assertEquals(1794700060L, c.nowSeconds(65000));
        assertEquals(1794700000L + 86400, c.nowSeconds(5000 + 86400_000L));
    }

    @Test
    public void elapsedGoingBackwards_neverReturnsLessThanSyncedTime() {
        RelayClock c = new RelayClock();
        c.sync(1794700000L, 5000);
        assertEquals(1794700000L, c.nowSeconds(4999));
        assertEquals(1794700000L, c.nowSeconds(0));
        assertEquals(1794700000L, c.nowSeconds(-1_000_000));
    }

    @Test
    public void resync_replacesReference() {
        RelayClock c = new RelayClock();
        c.sync(1794700000L, 5000);
        c.sync(1794700100L, 90000);
        assertEquals(1794700100L, c.nowSeconds(90000));
        assertEquals(1794700105L, c.nowSeconds(95000));
    }
}
