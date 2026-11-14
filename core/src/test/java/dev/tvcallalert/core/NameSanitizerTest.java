package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class NameSanitizerTest {
    private static final String FB = "Phone 1";

    /** Invariants every output must hold, whatever the input was. */
    private static void assertSafe(String input, String out) {
        if (out.equals(FB)) {
            return;
        }
        assertFalse("empty for " + input, out.isEmpty());
        assertTrue("too long: " + out, out.codePointCount(0, out.length()) <= Limits.MAX_NAME_CHARS);
        assertFalse("leading space: [" + out + "]", out.startsWith(" "));
        assertFalse("trailing space: [" + out + "]", out.endsWith(" "));
        assertFalse("double space: [" + out + "]", out.contains("  "));
        int i = 0;
        while (i < out.length()) {
            int cp = out.codePointAt(i);
            i += Character.charCount(cp);
            assertTrue("unexpected code point U+" + Integer.toHexString(cp) + " in [" + out + "]",
                    cp == ' ' || Character.isLetterOrDigit(cp));
            assertFalse("lone surrogate in output",
                    cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
        }
    }

    private static String clean(String input) {
        String out = NameSanitizer.clean(input, FB);
        assertSafe(input, out);
        return out;
    }

    @Test
    public void null_returnsFallback() {
        assertEquals(FB, clean(null));
        assertNull(NameSanitizer.clean(null, null));
    }

    @Test
    public void empty_returnsFallback() {
        assertEquals(FB, clean(""));
    }

    @Test
    public void onlySpaces_returnsFallback() {
        assertEquals(FB, clean("     "));
        assertEquals(FB, clean("  　"));
    }

    @Test
    public void onlySymbols_returnsFallback() {
        assertEquals(FB, clean("!@#$%^&*()"));
        assertEquals(FB, clean("<>&\"'/\\"));
        assertEquals(FB, clean("-_-"));
        assertEquals(FB, clean(" . , ; "));
    }

    @Test
    public void plainName_kept() {
        assertEquals("Mum", clean("Mum"));
        assertEquals("Dad 2", clean("Dad 2"));
    }

    @Test
    public void symbolsInsideName_dropped() {
        assertEquals("OBrien", clean("O'Brien"));
        assertEquals("scriptalert1scri", clean("<script>alert(1)</script>"));
        assertEquals("AB", clean("A|B"));
        assertEquals("ab", clean("a%b"));
    }

    @Test
    public void overLong_cutAtSixteenCharacters() {
        assertEquals("abcdefghijklmnop", clean("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("abcdefghijklmnop", clean("abcdefghijklmnop"));
        assertEquals(16, clean(TestSupport.repeat('x', 100000)).length());
    }

    @Test
    public void overLongEndingOnSpace_hasNoTrailingSpace() {
        assertEquals("abcdefghijklmno", clean("abcdefghijklmno pqrstuvwxyz"));
        assertEquals("a b c d e f g h", clean("a b c d e f g h i j k l"));
    }

    @Test
    public void symbolsDoNotCountTowardsTheLimit() {
        assertEquals("abcdefghijklmnop", clean("!!!!!!!!!!!!!!!!!!!!a-b-c-d-e-f-g-h-i-j-k-l-m-n-o-p"));
    }

    @Test
    public void controlCharacters_removed() {
        assertEquals("John", clean("Jo\u0000hn\u0007"));
        assertEquals("A31mB", clean("A\u001b[31mB"));
        assertEquals("AB", clean("A\u007fB"));
        assertEquals("AB", clean("A\u0085B"));
        String out = clean("line1\nline2\r\n\tx");
        assertFalse(out.contains("\n"));
        assertFalse(out.contains("\r"));
        assertFalse(out.contains("\t"));
    }

    @Test
    public void bidiAndInvisibleCharacters_removed() {
        assertEquals("evilName", clean("‮evilName"));
        assertEquals("abcdef", clean("abc‮def‬"));
        assertEquals("ab", clean("a​b"));
        assertEquals("ab", clean("a‎‏⁦⁧⁨⁩؜﻿b"));
        assertEquals("ab", clean("a‍‌b"));
    }

    @Test
    public void emoji_removed() {
        assertEquals("Sam", clean("Sam 😀"));
        assertEquals("Sam", clean("😀Sam😀"));
        assertEquals(FB, clean("😀👨‍👩‍👧"));
        assertEquals("A B", clean("A ❤️ B"));
    }

    @Test
    public void supplementaryLetters_keptWholeAndCountedOnce() {
        // U+20000 is a CJK ideograph outside the BMP: one letter, two Java chars.
        String han = "𠀀";
        assertEquals(han, clean(han));
        StringBuilder twenty = new StringBuilder();
        StringBuilder sixteen = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            twenty.append(han);
            if (i < 16) {
                sixteen.append(han);
            }
        }
        assertEquals(sixteen.toString(), clean(twenty.toString()));
    }

    @Test
    public void loneSurrogates_removed() {
        assertEquals("Bob", clean("\uD83DBob"));
        assertEquals("Bob", clean("Bob\uDE00"));
        assertEquals("Bob", clean("B\uDC00o\uD800b"));
        assertEquals(FB, clean("\uD800"));
    }

    @Test
    public void multipleSpaces_collapsedToOne() {
        assertEquals("A B", clean("A   B"));
        assertEquals("A B C", clean("A  B     C"));
        assertEquals("A B", clean("A    B"));
    }

    @Test
    public void spacesSeparatedBySymbols_stillCollapse() {
        assertEquals("A B", clean("A - B"));
        assertEquals("A B", clean("A . , . B"));
    }

    @Test
    public void leadingAndTrailingSpaces_trimmed() {
        assertEquals("Bob", clean("  Bob  "));
        assertEquals("Bob", clean(" Bob　"));
        assertEquals("Bob", clean(" ! Bob ! "));
    }

    @Test
    public void nonLatinLetters_kept() {
        assertEquals("李雷", clean("李雷"));
        assertEquals("Мария", clean("Мария"));
        assertEquals("محمد", clean("محمد"));
        assertEquals("José", clean("José"));
        assertEquals("さくら", clean("さくら"));
        assertEquals("Ιωάννης",
                clean("Ιωάννης"));
    }

    @Test
    public void randomInput_alwaysSafe() {
        Random rnd = new Random(77);
        for (int i = 0; i < 20000; i++) {
            int len = rnd.nextInt(40);
            StringBuilder sb = new StringBuilder(len);
            for (int j = 0; j < len; j++) {
                int pick = rnd.nextInt(6);
                if (pick == 0) {
                    sb.append(' ');
                } else if (pick < 3) {
                    sb.append((char) (0x20 + rnd.nextInt(0x60)));
                } else {
                    sb.append((char) rnd.nextInt(0x10000));
                }
            }
            clean(sb.toString());
        }
    }
}
