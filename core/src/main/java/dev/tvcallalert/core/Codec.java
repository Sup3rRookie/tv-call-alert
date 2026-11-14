package dev.tvcallalert.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Text encodings used by the protocol. All inputs are treated as untrusted. */
public final class Codec {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int SEED_CODE_CHARS = 26;

    private Codec() {
    }

    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = HEX[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[data[i] & 0xF];
        }
        return new String(out);
    }

    /** RFC 4648 base32 without padding. */
    public static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = ((buffer << 8) | (b & 0xFF)) & 0xFFFF;
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    /**
     * Decodes a pairing code: 26 base32 characters, with spaces and hyphens allowed anywhere
     * and case ignored.
     *
     * @return the 16 seed bytes, or null when the code is not a canonical encoding
     */
    public static byte[] parseSeedCode(String code) {
        if (code == null || code.length() > 64) {
            return null;
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 'a' + 'A');
            }
            if (BASE32.indexOf(c) < 0) {
                return null;
            }
            clean.append(c);
        }
        if (clean.length() != SEED_CODE_CHARS) {
            return null;
        }
        byte[] out = new byte[Limits.SEED_BYTES];
        int buffer = 0;
        int bits = 0;
        int n = 0;
        for (int i = 0; i < clean.length(); i++) {
            buffer = (buffer << 5) | BASE32.indexOf(clean.charAt(i));
            bits += 5;
            if (bits >= 8) {
                out[n++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
            buffer &= (1 << bits) - 1;
        }
        // 26 characters carry 130 bits: 16 bytes plus 2 padding bits that must be zero.
        if (n != out.length || buffer != 0) {
            return null;
        }
        return out;
    }

    /** Formats a seed as a grouped pairing code, for example ABCD-EFGH-...-XY. */
    public static String formatSeedCode(byte[] seed) {
        String raw = base32(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(raw.charAt(i));
        }
        return sb.toString();
    }
}
