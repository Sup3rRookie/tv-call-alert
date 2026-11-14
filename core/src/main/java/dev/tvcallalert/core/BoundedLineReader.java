package dev.tvcallalert.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-terminated lines with a hard length cap. An over-long line is consumed and
 * discarded without being buffered.
 */
public final class BoundedLineReader {
    private final InputStream in;
    private final int maxBytes;
    private final byte[] chunk = new byte[4096];
    private int chunkLen;
    private int chunkPos;

    public BoundedLineReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /**
     * Reads the next line.
     *
     * @return the line as UTF-8 text without its terminator, an empty string for an
     *         over-long or empty line, or null at end of stream
     */
    public String readLine() throws IOException {
        byte[] line = new byte[Math.min(maxBytes, 1024)];
        int len = 0;
        boolean overflow = false;
        boolean any = false;
        while (true) {
            if (chunkPos == chunkLen) {
                chunkLen = in.read(chunk, 0, chunk.length);
                chunkPos = 0;
                if (chunkLen <= 0) {
                    chunkLen = 0;
                    if (!any) {
                        return null;
                    }
                    break;
                }
            }
            byte b = chunk[chunkPos++];
            any = true;
            if (b == '\n') {
                break;
            }
            if (overflow) {
                continue;
            }
            if (len == maxBytes) {
                overflow = true;
                continue;
            }
            if (len == line.length) {
                byte[] bigger = new byte[Math.min(maxBytes, line.length * 2)];
                System.arraycopy(line, 0, bigger, 0, len);
                line = bigger;
            }
            line[len++] = b;
        }
        if (overflow) {
            return "";
        }
        if (len > 0 && line[len - 1] == '\r') {
            len--;
        }
        return new String(line, 0, len, StandardCharsets.UTF_8);
    }
}
