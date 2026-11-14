package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class KdfTest {
    private static final byte[] KEY = Codec.ascii("0123456789abcdef0123456789abcdef");
    private static final String PART = "RT1|alert|1|1794700000|AAAAAAAAAAAAAAAAAAAAAA|-";

    @Test
    public void hmac_matchesRfc4231Case2() {
        byte[] out = Kdf.hmac(Codec.ascii("Jefe"), Codec.ascii("what do ya want for nothing?"));
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Codec.hex(out));
    }

    @Test
    public void sign_is43Base64UrlChars() {
        String mac = Kdf.sign(KEY, PART);
        assertTrue(mac, MessageParser.isToken(mac, Limits.MAC_CHARS));
    }

    @Test
    public void correctSignature_verifies() {
        assertTrue(Kdf.verify(KEY, PART, Kdf.sign(KEY, PART)));
    }

    @Test
    public void everySingleFlippedCharacter_failsVerification() {
        String mac = Kdf.sign(KEY, PART);
        for (int i = 0; i < mac.length(); i++) {
            char[] c = mac.toCharArray();
            c[i] = c[i] == 'A' ? 'B' : 'A';
            assertFalse("flip at " + i, Kdf.verify(KEY, PART, new String(c)));
        }
    }

    @Test
    public void caseFlippedSignature_fails() {
        String mac = Kdf.sign(KEY, PART);
        assertFalse(Kdf.verify(KEY, PART, mac.toLowerCase()));
        assertFalse(Kdf.verify(KEY, PART, mac.toUpperCase()));
    }

    @Test
    public void truncatedSignature_fails() {
        String mac = Kdf.sign(KEY, PART);
        for (int i = 0; i < mac.length(); i++) {
            assertFalse("prefix " + i, Kdf.verify(KEY, PART, mac.substring(0, i)));
        }
    }

    @Test
    public void extendedSignature_fails() {
        String mac = Kdf.sign(KEY, PART);
        assertFalse(Kdf.verify(KEY, PART, mac + "A"));
        assertFalse(Kdf.verify(KEY, PART, mac + "="));
        assertFalse(Kdf.verify(KEY, PART, mac + mac));
    }

    @Test
    public void emptySignature_fails() {
        assertFalse(Kdf.verify(KEY, PART, ""));
    }

    @Test
    public void wrongKey_fails() {
        byte[] other = KEY.clone();
        other[0] ^= 1;
        assertFalse(Kdf.verify(other, PART, Kdf.sign(KEY, PART)));
    }

    @Test
    public void signatureOfDifferentPayload_fails() {
        String mac = Kdf.sign(KEY, PART);
        assertFalse(Kdf.verify(KEY, PART.replace("alert", "ping"), mac));
        assertFalse(Kdf.verify(KEY, PART.replace("|1|", "|2|"), mac));
        assertFalse(Kdf.verify(KEY, PART + " ", mac));
        assertFalse(Kdf.verify(KEY, "", mac));
    }

    @Test
    public void nonAsciiSignature_failsWithoutThrowing() {
        String mac = Kdf.sign(KEY, PART);
        assertFalse(Kdf.verify(KEY, PART, mac.substring(0, 42) + "é"));
        assertFalse(Kdf.verify(KEY, PART, TestSupport.repeat('?', 43)));
    }

    @Test
    public void derive_authAndAckKeysDiffer() {
        SlotKeys k = Kdf.derive(TestSupport.seed(0));
        assertEquals(32, k.authKey().length);
        assertEquals(32, k.ackKey().length);
        assertNotEquals(Codec.hex(k.authKey()), Codec.hex(k.ackKey()));
        assertNotEquals(k.topicDown, k.topicAck);
    }

    @Test
    public void derive_topicsAreRtPlus40Hex() {
        SlotKeys k = Kdf.derive(TestSupport.seed(7));
        assertTrue(k.topicDown, k.topicDown.matches("rt[0-9a-f]{40}"));
        assertTrue(k.topicAck, k.topicAck.matches("rt[0-9a-f]{40}"));
    }

    @Test
    public void derive_isDeterministicAndSeedDependent() {
        assertEquals(Kdf.derive(TestSupport.seed(1)).topicDown,
                Kdf.derive(TestSupport.seed(1)).topicDown);
        assertNotEquals(Kdf.derive(TestSupport.seed(1)).topicDown,
                Kdf.derive(TestSupport.seed(2)).topicDown);
    }

    @Test
    public void ackKeySignature_doesNotVerifyAsAuth() {
        SlotKeys k = Kdf.derive(TestSupport.seed(0));
        assertFalse(Kdf.verify(k.authKey(), PART, Kdf.sign(k.ackKey(), PART)));
    }

    @Test
    public void deriveWithBadSeed_throwsIllegalArgument() {
        byte[][] bad = {null, new byte[0], new byte[15], new byte[17], new byte[32]};
        for (byte[] seed : bad) {
            try {
                Kdf.derive(seed);
                fail("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
