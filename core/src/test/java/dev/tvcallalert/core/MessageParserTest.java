package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class MessageParserTest {
    private static final String NONCE = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String REF = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final String MAC = "rho7lHAItv5gJfxASnPJSEZ-kYDtR4MyRgs3OQFyICE";
    private static final String TS = "1794700000";
    private static final String VALID = "RT1|alert|1|" + TS + "|" + NONCE + "|-|" + MAC;

    private static String line(String type, String slot, String ts, String nonce, String ref,
            String mac) {
        return "RT1|" + type + "|" + slot + "|" + ts + "|" + nonce + "|" + ref + "|" + mac;
    }

    private static void assertRejected(Rejection expected, String raw) {
        MessageParser.Result r = MessageParser.parse(raw);
        assertNull("must not parse: " + raw, r.message);
        assertEquals(raw, expected, r.rejection);
    }

    private static WireMessage assertParsed(String raw) {
        MessageParser.Result r = MessageParser.parse(raw);
        assertNull(raw, r.rejection);
        assertNotNull(raw, r.message);
        return r.message;
    }

    @Test
    public void validAlert_parsesAllFields() {
        WireMessage m = assertParsed(VALID);
        assertEquals(WireMessage.Type.ALERT, m.type);
        assertEquals(1, m.slot);
        assertEquals(1794700000L, m.ts);
        assertEquals(NONCE, m.nonce);
        assertNull(m.ref);
        assertEquals(MAC, m.mac);
        assertEquals("RT1|alert|1|" + TS + "|" + NONCE + "|-", m.signedPart);
    }

    @Test
    public void validCancel_carriesRef() {
        WireMessage m = assertParsed(line("cancel", "2", TS, NONCE, REF, MAC));
        assertEquals(WireMessage.Type.CANCEL, m.type);
        assertEquals(2, m.slot);
        assertEquals(REF, m.ref);
        assertEquals("RT1|cancel|2|" + TS + "|" + NONCE + "|" + REF, m.signedPart);
    }

    @Test
    public void validPing_parses() {
        WireMessage m = assertParsed(line("ping", "4", TS, NONCE, "-", MAC));
        assertEquals(WireMessage.Type.PING, m.type);
        assertNull(m.ref);
    }

    @Test
    public void slots1To4_accepted() {
        for (int s = 1; s <= 4; s++) {
            assertEquals(s, assertParsed(line("alert", "" + s, TS, NONCE, "-", MAC)).slot);
        }
    }

    @Test
    public void tsBoundaries_accepted() {
        assertEquals(1000000000L,
                assertParsed(line("alert", "1", "1000000000", NONCE, "-", MAC)).ts);
        assertEquals(9999999999L,
                assertParsed(line("alert", "1", "9999999999", NONCE, "-", MAC)).ts);
    }

    @Test
    public void nonceAndRefWithUrlSafeChars_accepted() {
        assertParsed(line("alert", "1", TS, "_-_-_-_-_-_-_-_-_-_-_-", "-", MAC));
        assertParsed(line("cancel", "1", TS, NONCE, "-_-_-_-_-_-_-_-_-_-_-_", MAC));
    }

    @Test
    public void null_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, null);
    }

    @Test
    public void empty_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, "");
    }

    @Test
    public void length201_rejectedAsTooLarge() {
        assertRejected(Rejection.TOO_LARGE, TestSupport.repeat('A', 201));
        assertRejected(Rejection.TOO_LARGE, VALID + TestSupport.repeat('A', 201 - VALID.length()));
    }

    @Test
    public void length200_isNotTooLarge() {
        assertRejected(Rejection.BAD_FORMAT, TestSupport.repeat('A', 200));
        assertRejected(Rejection.BAD_FORMAT, VALID + TestSupport.repeat('A', 200 - VALID.length()));
    }

    @Test(timeout = 5000)
    public void multiMegabyteString_rejectedAsTooLargeWithoutScanningIt() {
        String huge = TestSupport.repeat('|', 5 * 1024 * 1024);
        for (int i = 0; i < 100000; i++) {
            MessageParser.Result r = MessageParser.parse(huge);
            assertNull(r.message);
            assertEquals(Rejection.TOO_LARGE, r.rejection);
        }
    }

    @Test
    public void tooLargeNonAscii_reportedAsTooLarge() {
        assertRejected(Rejection.TOO_LARGE, TestSupport.repeat('é', 201));
    }

    @Test
    public void nonAscii_rejectedAsBadCharset() {
        assertRejected(Rejection.BAD_CHARSET, VALID.replace(NONCE, NONCE.substring(1) + "é"));
        // Cyrillic small a looks like Latin a.
        assertRejected(Rejection.BAD_CHARSET, VALID.replace("alert", "аlert"));
        assertRejected(Rejection.BAD_CHARSET, "﻿" + VALID);
        assertRejected(Rejection.BAD_CHARSET, VALID + "😀");
        assertRejected(Rejection.BAD_CHARSET, VALID.replace("|", "｜"));
    }

    @Test
    public void nonAsciiDigits_rejectedAsBadCharset() {
        // Arabic-Indic and fullwidth digits pass Character.isDigit but are not protocol digits.
        assertRejected(Rejection.BAD_CHARSET,
                line("alert", "1", "179470000٠", NONCE, "-", MAC));
        assertRejected(Rejection.BAD_CHARSET, line("alert", "１", TS, NONCE, "-", MAC));
    }

    @Test
    public void controlChars_rejectedAsBadCharset() {
        for (char c = 0; c < 0x20; c++) {
            assertRejected(Rejection.BAD_CHARSET, VALID.substring(0, 20) + c + VALID.substring(21));
            assertRejected(Rejection.BAD_CHARSET, VALID + c);
            assertRejected(Rejection.BAD_CHARSET, c + VALID);
        }
        assertRejected(Rejection.BAD_CHARSET, VALID + '\u007f');
        assertRejected(Rejection.BAD_CHARSET, VALID + '\u0080');
    }

    @Test
    public void nul_rejectedAsBadCharset() {
        assertRejected(Rejection.BAD_CHARSET, "\u0000");
        assertRejected(Rejection.BAD_CHARSET, VALID + "\u0000");
        assertRejected(Rejection.BAD_CHARSET, VALID.replace("alert", "alert\u0000"));
    }

    @Test
    public void tab_rejectedAsBadCharset() {
        assertRejected(Rejection.BAD_CHARSET, VALID.replace('|', '\t'));
        assertRejected(Rejection.BAD_CHARSET, VALID + "\t");
    }

    @Test
    public void space_rejectedAsBadCharset() {
        assertRejected(Rejection.BAD_CHARSET, " " + VALID);
        assertRejected(Rejection.BAD_CHARSET, VALID + " ");
        assertRejected(Rejection.BAD_CHARSET, VALID.replace("|", " | "));
        assertRejected(Rejection.BAD_CHARSET, line("alert", " 1", TS, NONCE, "-", MAC));
        assertRejected(Rejection.BAD_CHARSET, " ");
    }

    @Test
    public void trailingNewline_rejectedAsBadCharset() {
        assertRejected(Rejection.BAD_CHARSET, VALID + "\n");
        assertRejected(Rejection.BAD_CHARSET, VALID + "\r\n");
        assertRejected(Rejection.BAD_CHARSET, VALID + "\r");
        assertRejected(Rejection.BAD_CHARSET, "\n" + VALID);
    }

    @Test
    public void twoMessagesJoinedByNewline_rejected() {
        assertRejected(Rejection.BAD_CHARSET, VALID + "\n" + VALID);
    }

    @Test
    public void wrongFieldCount_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, "RT1");
        assertRejected(Rejection.BAD_FORMAT, "|");
        assertRejected(Rejection.BAD_FORMAT, "||||||");
        assertRejected(Rejection.BAD_FORMAT, "RT1|alert|1|" + TS + "|" + NONCE + "|" + MAC);
        assertRejected(Rejection.BAD_FORMAT, "RT1|alert|1|" + TS + "|" + NONCE + "|-");
        assertRejected(Rejection.BAD_FORMAT, VALID + "|extra");
        assertRejected(Rejection.BAD_FORMAT, "RT1|alert|1|" + TS + "|" + NONCE + "|-|-|" + MAC);
    }

    @Test
    public void trailingPipe_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, VALID + "|");
    }

    @Test
    public void leadingPipe_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, "|" + VALID);
    }

    @Test
    public void emptyFields_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, "|alert|1|" + TS + "|" + NONCE + "|-|" + MAC);
        assertRejected(Rejection.BAD_FORMAT, line("", "1", TS, NONCE, "-", MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "", TS, NONCE, "-", MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", "", NONCE, "-", MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, "", "-", MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, NONCE, "", MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, NONCE, "-", ""));
        assertRejected(Rejection.BAD_FORMAT, line("cancel", "1", TS, NONCE, "", MAC));
    }

    @Test
    public void wrongPrefix_rejectedAsBadFormat() {
        for (String p : new String[] {"RT2", "rt1", "RA1", "RT", "RT11", "XRT1", "Rt1", "RT0"}) {
            assertRejected(Rejection.BAD_FORMAT, p + VALID.substring(3));
        }
    }

    @Test
    public void unknownType_rejectedAsBadFormat() {
        for (String t : new String[] {"ALERT", "Alert", "alerts", "aler", "pong", "show",
                "cancelled", "PING", "alert,ping", "*"}) {
            assertRejected(Rejection.BAD_FORMAT, line(t, "1", TS, NONCE, "-", MAC));
        }
    }

    @Test
    public void badSlot_rejectedAsBadFormat() {
        for (String s : new String[] {"0", "5", "9", "12", "01", "11", "a", "-1", "+1", "1.",
                "0x1"}) {
            assertRejected(Rejection.BAD_FORMAT, line("alert", s, TS, NONCE, "-", MAC));
        }
    }

    @Test
    public void badTs_rejectedAsBadFormat() {
        for (String t : new String[] {"179470000", "17947000000", "0794700000", "0000000000",
                "17947000a0", "a794700000", "+794700000", "-794700000", "-1794700000",
                "1794700.00", "1.7947e+09", "0x6AF8A3E0", "1794700000000", "1",
                "99999999999999999999"}) {
            assertRejected(Rejection.BAD_FORMAT, line("alert", "1", t, NONCE, "-", MAC));
        }
    }

    @Test
    public void badNonce_rejectedAsBadFormat() {
        String n21 = NONCE.substring(1);
        for (String n : new String[] {n21, NONCE + "A", n21 + "+", n21 + "/", n21 + "=",
                n21 + ".", n21 + "*", n21 + "~", "-", NONCE + NONCE}) {
            assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, n, "-", MAC));
        }
    }

    @Test
    public void badMac_rejectedAsBadFormat() {
        String m42 = MAC.substring(1);
        for (String m : new String[] {m42, MAC + "A", MAC + "=", m42 + "+", m42 + "/",
                m42 + "=", m42 + ".", "-", "+" + m42}) {
            assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, NONCE, "-", m));
        }
    }

    @Test
    public void dashRefOnCancel_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, line("cancel", "1", TS, NONCE, "-", MAC));
    }

    @Test
    public void badRefOnCancel_rejectedAsBadFormat() {
        String r21 = REF.substring(1);
        for (String r : new String[] {r21, REF + "B", r21 + "+", r21 + "/", r21 + "="}) {
            assertRejected(Rejection.BAD_FORMAT, line("cancel", "1", TS, NONCE, r, MAC));
        }
    }

    @Test
    public void tokenRefOnAlert_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, NONCE, REF, MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, NONCE, "--", MAC));
        assertRejected(Rejection.BAD_FORMAT, line("alert", "1", TS, NONCE, "x", MAC));
    }

    @Test
    public void tokenRefOnPing_rejectedAsBadFormat() {
        assertRejected(Rejection.BAD_FORMAT, line("ping", "1", TS, NONCE, REF, MAC));
    }

    @Test
    public void randomGarbage_neverThrowsAndNeverParses() {
        Random rnd = new Random(1234);
        for (int i = 0; i < 20000; i++) {
            int len = rnd.nextInt(260);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                int pick = rnd.nextInt(10);
                if (pick < 3) {
                    sb.append('|');
                } else if (pick < 9) {
                    sb.append((char) (0x20 + rnd.nextInt(0x60)));
                } else {
                    sb.append((char) rnd.nextInt(0x10000));
                }
            }
            MessageParser.Result r = MessageParser.parse(sb.toString());
            assertNull(r.message);
            assertNotNull(r.rejection);
        }
    }

    @Test
    public void singleCharMutations_neverThrowAndStayConsistent() {
        Random rnd = new Random(99);
        String valid = line("cancel", "3", TS, NONCE, REF, MAC);
        for (int i = 0; i < 20000; i++) {
            char[] c = valid.toCharArray();
            c[rnd.nextInt(c.length)] = (char) rnd.nextInt(0x100);
            String raw = new String(c);
            MessageParser.Result r = MessageParser.parse(raw);
            assertTrue((r.message == null) != (r.rejection == null));
            if (r.message != null) {
                assertEquals(raw, r.message.signedPart + "|" + r.message.mac);
                assertTrue(r.message.slot >= 1 && r.message.slot <= 4);
                assertEquals(22, r.message.nonce.length());
                assertEquals(43, r.message.mac.length());
            }
        }
    }

    @Test
    public void truncations_neverParse() {
        for (int i = 0; i < VALID.length(); i++) {
            assertNull(MessageParser.parse(VALID.substring(0, i)).message);
            assertNull(MessageParser.parse(VALID.substring(VALID.length() - i)).message);
        }
    }
}
