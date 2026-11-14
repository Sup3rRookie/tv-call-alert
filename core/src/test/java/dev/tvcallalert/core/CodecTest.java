package dev.tvcallalert.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.Test;

public class CodecTest {
    private static final String CODE = "AAAQEAYEAUDAOCAJBIFQYDIOB4";
    private static final String GROUPED = "AAAQ-EAYE-AUDA-OCAJ-BIFQ-YDIO-B4";
    private static final byte[] SEED = TestSupport.seed(0);

    @Test
    public void base32_matchesRfc4648Vectors() {
        String[][] vectors = {{"", ""}, {"f", "MY"}, {"fo", "MZXQ"}, {"foo", "MZXW6"},
            {"foob", "MZXW6YQ"}, {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI"}};
        for (String[] v : vectors) {
            assertEquals(v[1], Codec.base32(v[0].getBytes(StandardCharsets.US_ASCII)));
        }
    }

    @Test
    public void hex_isLowercaseAndHandlesHighBytes() {
        assertEquals("00017f80ff", Codec.hex(new byte[] {0, 1, 127, (byte) 128, (byte) 255}));
        assertEquals("", Codec.hex(new byte[0]));
    }

    @Test
    public void base64Url_isUnpaddedUrlAlphabet() {
        assertEquals("-_-_", Codec.base64Url(new byte[] {(byte) 0xFB, (byte) 0xFF, (byte) 0xBF}));
        assertEquals("AA", Codec.base64Url(new byte[] {0}));
        assertEquals(22, Codec.base64Url(new byte[16]).length());
        assertEquals(43, Codec.base64Url(new byte[32]).length());
    }

    @Test
    public void formatSeedCode_groupsInFoursWithHyphens() {
        assertEquals(GROUPED, Codec.formatSeedCode(SEED));
    }

    @Test
    public void canonicalCode_parses() {
        assertArrayEquals(SEED, Codec.parseSeedCode(CODE));
        assertArrayEquals(SEED, Codec.parseSeedCode(GROUPED));
    }

    @Test
    public void lowercase_parses() {
        assertArrayEquals(SEED, Codec.parseSeedCode(CODE.toLowerCase()));
        assertArrayEquals(SEED, Codec.parseSeedCode("aaaq-EAYE-audA-OCAJ-bifq-YDIO-b4"));
    }

    @Test
    public void spacesAndHyphens_ignoredAnywhere() {
        assertArrayEquals(SEED, Codec.parseSeedCode("AAAQ EAYE AUDA OCAJ BIFQ YDIO B4"));
        assertArrayEquals(SEED, Codec.parseSeedCode("  " + GROUPED + "  "));
        assertArrayEquals(SEED, Codec.parseSeedCode("-A-A-A-Q--EAYEAUDAOCAJBIFQYDIOB 4-"));
    }

    @Test
    public void randomSeeds_roundTrip() {
        Random rnd = new Random(2026);
        for (int i = 0; i < 2000; i++) {
            byte[] seed = new byte[Limits.SEED_BYTES];
            rnd.nextBytes(seed);
            String code = Codec.formatSeedCode(seed);
            assertEquals(32, code.length());
            assertArrayEquals(seed, Codec.parseSeedCode(code));
            assertArrayEquals(seed, Codec.parseSeedCode(Codec.base32(seed).toLowerCase()));
        }
    }

    @Test
    public void wrongLength_rejected() {
        assertNull(Codec.parseSeedCode(""));
        assertNull(Codec.parseSeedCode("A"));
        assertNull(Codec.parseSeedCode(CODE.substring(1)));
        assertNull(Codec.parseSeedCode(CODE.substring(0, 25)));
        assertNull(Codec.parseSeedCode(CODE + "A"));
        assertNull(Codec.parseSeedCode("A" + CODE));
        assertNull(Codec.parseSeedCode(CODE + CODE));
        assertNull(Codec.parseSeedCode("----"));
        assertNull(Codec.parseSeedCode("    "));
    }

    @Test
    public void invalidCharacters_rejected() {
        String chars = "0189!@#$%^&*()_+=.,;:/\\|?<>[]{}\"'`~\t\n\r\u0000";
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            String label = "char " + (int) c;
            assertNull(label, Codec.parseSeedCode(c + CODE.substring(1)));
            assertNull(label, Codec.parseSeedCode(CODE.substring(0, 25) + c));
            assertNull(label, Codec.parseSeedCode(CODE.substring(0, 12) + c + CODE.substring(13)));
            // An invalid character is not skipped like a separator would be.
            assertNull(label, Codec.parseSeedCode(CODE + c));
            assertNull(label, Codec.parseSeedCode(c + CODE));
        }
    }

    @Test
    public void nonAsciiLookalikes_rejected() {
        // Fullwidth A, Cyrillic A, Turkish dotless i, Kelvin sign, long s.
        for (char c : new char[] {'Ａ', 'А', 'ı', 'K', 'ſ', 'é'}) {
            assertNull("char " + (int) c, Codec.parseSeedCode(c + CODE.substring(1)));
        }
        // Non-breaking space and en dash are not accepted as separators.
        assertNull(Codec.parseSeedCode(GROUPED.replace('-', '–')));
        assertNull(Codec.parseSeedCode(GROUPED.replace('-', ' ')));
    }

    @Test
    public void paddedBase32_rejected() {
        assertNull(Codec.parseSeedCode(CODE + "======"));
    }

    @Test
    public void nonZeroPaddingBits_rejected() {
        // The last character carries 3 data bits and 2 padding bits.
        String head = CODE.substring(0, 25);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int accepted = 0;
        for (int i = 0; i < 32; i++) {
            byte[] parsed = Codec.parseSeedCode(head + alphabet.charAt(i));
            if ((i & 3) == 0) {
                assertNotNull("canonical tail " + alphabet.charAt(i), parsed);
                assertEquals(head + alphabet.charAt(i), Codec.base32(parsed));
                accepted++;
            } else {
                assertNull("non canonical tail " + alphabet.charAt(i), parsed);
            }
        }
        assertEquals(8, accepted);
        assertNull(Codec.parseSeedCode("77777777777777777777777777"));
        assertNull(Codec.parseSeedCode("AAAAAAAAAAAAAAAAAAAAAAAAAB"));
        assertArrayEquals(new byte[16], Codec.parseSeedCode("AAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    public void null_rejected() {
        assertNull(Codec.parseSeedCode(null));
    }

    @Test(timeout = 5000)
    public void veryLongInput_rejectedQuickly() {
        String huge = TestSupport.repeat('A', 5 * 1024 * 1024);
        String hugeSeparators = TestSupport.repeat('-', 5 * 1024 * 1024);
        for (int i = 0; i < 100000; i++) {
            assertNull(Codec.parseSeedCode(huge));
            assertNull(Codec.parseSeedCode(hugeSeparators));
        }
        assertNull(Codec.parseSeedCode(TestSupport.repeat('A', 65)));
        assertNull(Codec.parseSeedCode(CODE + TestSupport.repeat(' ', 1000)));
    }

    @Test
    public void pairingStringWithSlotPrefix_isNotASeedCode() {
        // The phone strips the slot digit itself: the raw pairing string must not parse.
        assertNull(Codec.parseSeedCode("1-" + GROUPED));
    }

    @Test
    public void parsedSeed_isAlways16Bytes() {
        byte[] parsed = Codec.parseSeedCode("77777777777777777777777774");
        assertNotNull(parsed);
        assertEquals(16, parsed.length);
        for (byte b : parsed) {
            assertTrue(b == (byte) 0xFF);
        }
    }
}
