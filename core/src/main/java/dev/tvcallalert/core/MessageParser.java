package dev.tvcallalert.core;

/**
 * Strict parser for {@code RT1|type|slot|ts|nonce|ref|mac}. Never throws on any input.
 */
public final class MessageParser {
    /** Either a message or the reason it was rejected. */
    public static final class Result {
        public final WireMessage message;
        public final Rejection rejection;

        private Result(WireMessage message, Rejection rejection) {
            this.message = message;
            this.rejection = rejection;
        }
    }

    private static final int FIELD_COUNT = 7;

    private MessageParser() {
    }

    public static Result parse(String raw) {
        if (raw == null) {
            return reject(Rejection.BAD_FORMAT);
        }
        if (raw.length() > Limits.MAX_MESSAGE_CHARS) {
            return reject(Rejection.TOO_LARGE);
        }
        int pipes = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return reject(Rejection.BAD_CHARSET);
            }
            if (c == '|') {
                pipes++;
            }
        }
        if (pipes != FIELD_COUNT - 1) {
            return reject(Rejection.BAD_FORMAT);
        }
        String[] f = raw.split("\\|", -1);
        if (f.length != FIELD_COUNT || !"RT1".equals(f[0])) {
            return reject(Rejection.BAD_FORMAT);
        }
        WireMessage.Type type;
        if ("alert".equals(f[1])) {
            type = WireMessage.Type.ALERT;
        } else if ("cancel".equals(f[1])) {
            type = WireMessage.Type.CANCEL;
        } else if ("ping".equals(f[1])) {
            type = WireMessage.Type.PING;
        } else {
            return reject(Rejection.BAD_FORMAT);
        }
        if (f[2].length() != 1 || f[2].charAt(0) < '1'
                || f[2].charAt(0) > (char) ('0' + Limits.MAX_SLOTS)) {
            return reject(Rejection.BAD_FORMAT);
        }
        int slot = f[2].charAt(0) - '0';
        if (f[3].length() != 10 || !allDigits(f[3]) || f[3].charAt(0) == '0') {
            return reject(Rejection.BAD_FORMAT);
        }
        long ts = Long.parseLong(f[3]);
        if (!isToken(f[4], Limits.NONCE_CHARS) || !isToken(f[6], Limits.MAC_CHARS)) {
            return reject(Rejection.BAD_FORMAT);
        }
        String ref;
        if (type == WireMessage.Type.CANCEL) {
            if (!isToken(f[5], Limits.NONCE_CHARS)) {
                return reject(Rejection.BAD_FORMAT);
            }
            ref = f[5];
        } else {
            if (!"-".equals(f[5])) {
                return reject(Rejection.BAD_FORMAT);
            }
            ref = null;
        }
        String signedPart = raw.substring(0, raw.lastIndexOf('|'));
        return new Result(new WireMessage(type, slot, ts, f[4], ref, signedPart, f[6]), null);
    }

    private static Result reject(Rejection r) {
        return new Result(null, r);
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Exactly {@code length} base64url characters. */
    static boolean isToken(String s, int length) {
        if (s == null || s.length() != length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
