package dev.tvcallalert.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class BoundedLineReaderTest {
    private static final int CAP = 16;

    private static BoundedLineReader reader(String text, int cap) {
        return new BoundedLineReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), cap);
    }

    /** Hands out at most one byte per read call, like a slow socket. */
    private static final class TrickleStream extends InputStream {
        private final byte[] data;
        private int pos;

        TrickleStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            b[off] = data[pos++];
            return 1;
        }
    }

    /** Generates a huge line without ever holding it in memory, then a tail. */
    private static final class HugeLineStream extends InputStream {
        private long remaining;
        private final byte[] tail;
        private int tailPos;
        long served;

        HugeLineStream(long hugeBytes, String tail) {
            this.remaining = hugeBytes;
            this.tail = tail.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (remaining > 0) {
                int n = (int) Math.min(len, remaining);
                for (int i = 0; i < n; i++) {
                    b[off + i] = 'x';
                }
                remaining -= n;
                served += n;
                return n;
            }
            if (tailPos >= tail.length) {
                return -1;
            }
            int n = Math.min(len, tail.length - tailPos);
            System.arraycopy(tail, tailPos, b, off, n);
            tailPos += n;
            served += n;
            return n;
        }
    }

    @Test
    public void normalLines_readInOrderThenNull() throws IOException {
        BoundedLineReader r = reader("one\ntwo\nthree\n", CAP);
        assertEquals("one", r.readLine());
        assertEquals("two", r.readLine());
        assertEquals("three", r.readLine());
        assertNull(r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void emptyStream_returnsNull() throws IOException {
        assertNull(reader("", CAP).readLine());
    }

    @Test
    public void crlf_terminatorStripped() throws IOException {
        BoundedLineReader r = reader("one\r\ntwo\r\n\r\nlast", CAP);
        assertEquals("one", r.readLine());
        assertEquals("two", r.readLine());
        assertEquals("", r.readLine());
        assertEquals("last", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void carriageReturnInsideLine_kept() throws IOException {
        BoundedLineReader r = reader("a\rb\n", CAP);
        assertEquals("a\rb", r.readLine());
    }

    @Test
    public void emptyLines_returnedAsEmptyStrings() throws IOException {
        BoundedLineReader r = reader("\n\nx\n\n", CAP);
        assertEquals("", r.readLine());
        assertEquals("", r.readLine());
        assertEquals("x", r.readLine());
        assertEquals("", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void lastLineWithoutNewline_returned() throws IOException {
        BoundedLineReader r = reader("one\ntail", CAP);
        assertEquals("one", r.readLine());
        assertEquals("tail", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void lineExactlyAtCap_returnedWhole() throws IOException {
        String exact = TestSupport.repeat('a', CAP);
        BoundedLineReader r = reader(exact + "\nnext\n", CAP);
        assertEquals(exact, r.readLine());
        assertEquals("next", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void lineExactlyAtCapWithoutNewlineAtEof_returnedWhole() throws IOException {
        String exact = TestSupport.repeat('a', CAP);
        BoundedLineReader r = reader(exact, CAP);
        assertEquals(exact, r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void lineOneOverCap_returnedEmptyAndNextLineIntact() throws IOException {
        BoundedLineReader r = reader(TestSupport.repeat('a', CAP + 1) + "\nnext\n", CAP);
        assertEquals("", r.readLine());
        assertEquals("next", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void overLongLineAtEofWithoutNewline_returnedEmptyThenNull() throws IOException {
        BoundedLineReader r = reader("ok\n" + TestSupport.repeat('a', CAP * 3), CAP);
        assertEquals("ok", r.readLine());
        assertEquals("", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void capLargerThanInitialBuffer_growsUpToCapOnly() throws IOException {
        int cap = 5000;
        String exact = TestSupport.repeat('b', cap);
        BoundedLineReader r = reader(exact + "\n" + exact + "b\n" + "end\n", cap);
        assertEquals(exact, r.readLine());
        assertEquals("", r.readLine());
        assertEquals("end", r.readLine());
        assertNull(r.readLine());
    }

    @Test
    public void lineSpanningInternalChunks_readCorrectly() throws IOException {
        // The reader pulls 4096 byte chunks: put line breaks around that boundary.
        String a = TestSupport.repeat('a', 4090);
        String b = TestSupport.repeat('b', 20);
        BoundedLineReader r = reader(a + "\n" + b + "\r\n" + "c\n", 8192);
        assertEquals(a, r.readLine());
        assertEquals(b, r.readLine());
        assertEquals("c", r.readLine());
        assertNull(r.readLine());
    }

    @Test(timeout = 20000)
    public void fiveMegabyteLine_discardedAndFollowingLineReads() throws IOException {
        long huge = 5L * 1024 * 1024;
        HugeLineStream in = new HugeLineStream(huge, "\nafter\n");
        BoundedLineReader r = new BoundedLineReader(in, 4096);
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        assertEquals("", r.readLine());
        long after = rt.totalMemory() - rt.freeMemory();
        assertEquals("after", r.readLine());
        assertNull(r.readLine());
        assertEquals(huge + 7, in.served);
        // Loose bound: reading 5 MB must not have retained anything close to 5 MB.
        assertTrue("retained " + (after - before), after - before < 3L * 1024 * 1024);
    }

    @Test(timeout = 60000)
    public void manyOverLongLines_neverAccumulate() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append(TestSupport.repeat('z', 100 + i % 50)).append('\n').append("ok").append(i)
                    .append('\n');
        }
        BoundedLineReader r = reader(sb.toString(), 32);
        for (int i = 0; i < 2000; i++) {
            assertEquals("", r.readLine());
            assertEquals("ok" + i, r.readLine());
        }
        assertNull(r.readLine());
    }

    @Test
    public void invalidUtf8_doesNotThrowAndNextLineReads() throws IOException {
        byte[] bytes = {(byte) 0xC3, 0x28, (byte) 0xFF, (byte) 0xFE, 'a', '\n',
            (byte) 0xE2, (byte) 0x82, '\n', 'o', 'k', '\n'};
        BoundedLineReader r = new BoundedLineReader(new ByteArrayInputStream(bytes), CAP);
        String first = r.readLine();
        assertTrue(first, first.indexOf('�') >= 0);
        assertTrue(first.endsWith("a"));
        String second = r.readLine();
        assertTrue(second, second.indexOf('�') >= 0);
        assertEquals("ok", r.readLine());
        assertNull(r.readLine());
        // Whatever the decoder produced, the protocol parser must refuse it.
        assertNull(MessageParser.parse(first).message);
    }

    @Test
    public void validUtf8_decoded() throws IOException {
        BoundedLineReader r = reader("héllo 中\n", CAP);
        assertEquals("héllo 中", r.readLine());
    }

    @Test
    public void nulBytes_passedThroughWithoutEndingTheLine() throws IOException {
        BoundedLineReader r = reader("a\u0000b\nc\n", CAP);
        assertEquals("a\u0000b", r.readLine());
        assertEquals("c", r.readLine());
    }

    @Test
    public void oneBytePerRead_sameResultAsBulk() throws IOException {
        String text = "one\r\n\n" + TestSupport.repeat('a', CAP) + "\n"
                + TestSupport.repeat('b', CAP + 1) + "\nnext\ntail";
        BoundedLineReader r = new BoundedLineReader(
                new TrickleStream(text.getBytes(StandardCharsets.UTF_8)), CAP);
        assertEquals("one", r.readLine());
        assertEquals("", r.readLine());
        assertEquals(TestSupport.repeat('a', CAP), r.readLine());
        assertEquals("", r.readLine());
        assertEquals("next", r.readLine());
        assertEquals("tail", r.readLine());
        assertNull(r.readLine());
    }
}
