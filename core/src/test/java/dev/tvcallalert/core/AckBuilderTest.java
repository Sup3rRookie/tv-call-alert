package dev.tvcallalert.core;

import static dev.tvcallalert.core.TestSupport.nonce;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class AckBuilderTest {
    private static final SlotKeys KEYS = Kdf.derive(TestSupport.seed(0));
    private static final long TS = 1794700002L;

    private static void assertThrowsIllegalArgument(int slot, String ref) {
        try {
            AckBuilder.build(AckBuilder.Status.SHOWN, slot, TS, ref, KEYS);
            fail("expected IllegalArgumentException for slot " + slot + " ref " + ref);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void validAck_hasSixFieldsAndVerifiableMac() {
        String ack = AckBuilder.build(AckBuilder.Status.SHOWN, 3, TS, nonce(7), KEYS);
        String[] f = ack.split("\\|", -1);
        assertEquals(6, f.length);
        assertEquals("RA1", f[0]);
        assertEquals("shown", f[1]);
        assertEquals("3", f[2]);
        assertEquals("1794700002", f[3]);
        assertEquals(nonce(7), f[4]);
        assertTrue(MessageParser.isToken(f[5], Limits.MAC_CHARS));
        String signedPart = ack.substring(0, ack.lastIndexOf('|'));
        assertTrue(Kdf.verify(KEYS.ackKey(), signedPart, f[5]));
    }

    @Test
    public void ackMac_isNotValidUnderAuthKey() {
        String ack = AckBuilder.build(AckBuilder.Status.PONG, 1, TS, nonce(7), KEYS);
        int cut = ack.lastIndexOf('|');
        assertTrue(!Kdf.verify(KEYS.authKey(), ack.substring(0, cut), ack.substring(cut + 1)));
    }

    @Test
    public void ack_cannotBeParsedAsPhoneMessage() {
        String ack = AckBuilder.build(AckBuilder.Status.PONG, 1, TS, nonce(7), KEYS);
        assertEquals(Rejection.BAD_FORMAT, MessageParser.parse(ack).rejection);
    }

    @Test
    public void statusWireNames_matchProtocol() {
        String[] expected = {"shown", "dismissed", "expired", "cancelled", "asleep", "stale",
            "limited", "failed", "pong"};
        AckBuilder.Status[] values = AckBuilder.Status.values();
        assertEquals(expected.length, values.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], values[i].wire);
            String ack = AckBuilder.build(values[i], 1, TS, nonce(1), KEYS);
            assertTrue(ack, ack.startsWith("RA1|" + expected[i] + "|1|" + TS + "|"));
            assertTrue(ack.length() <= Limits.MAX_MESSAGE_CHARS);
        }
    }

    @Test
    public void differentFields_giveDifferentMacs() {
        String base = AckBuilder.build(AckBuilder.Status.SHOWN, 1, TS, nonce(1), KEYS);
        assertEquals(base, AckBuilder.build(AckBuilder.Status.SHOWN, 1, TS, nonce(1), KEYS));
        String[] others = {
            AckBuilder.build(AckBuilder.Status.DISMISSED, 1, TS, nonce(1), KEYS),
            AckBuilder.build(AckBuilder.Status.SHOWN, 2, TS, nonce(1), KEYS),
            AckBuilder.build(AckBuilder.Status.SHOWN, 1, TS + 1, nonce(1), KEYS),
            AckBuilder.build(AckBuilder.Status.SHOWN, 1, TS, nonce(2), KEYS),
            AckBuilder.build(AckBuilder.Status.SHOWN, 1, TS, nonce(1),
                    Kdf.derive(TestSupport.seed(50)))};
        String baseMac = base.substring(base.lastIndexOf('|') + 1);
        for (String o : others) {
            assertNotEquals(baseMac, o.substring(o.lastIndexOf('|') + 1));
        }
    }

    @Test
    public void allValidSlots_accepted() {
        for (int slot = 1; slot <= Limits.MAX_SLOTS; slot++) {
            String ack = AckBuilder.build(AckBuilder.Status.SHOWN, slot, TS, nonce(1), KEYS);
            assertTrue(ack.startsWith("RA1|shown|" + slot + "|"));
        }
    }

    @Test
    public void badSlot_throwsIllegalArgument() {
        for (int slot : new int[] {0, 5, -1, 10, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertThrowsIllegalArgument(slot, nonce(1));
        }
    }

    @Test
    public void badNonce_throwsIllegalArgument() {
        String n21 = nonce(1).substring(1);
        for (String ref : new String[] {null, "", "-", n21, nonce(1) + "0", n21 + "|",
                n21 + "+", n21 + "/", n21 + "=", n21 + " ", n21 + "\n", n21 + "é",
                "RA1|shown|1|1794700002", TestSupport.repeat('A', 100000)}) {
            assertThrowsIllegalArgument(1, ref);
        }
    }
}
