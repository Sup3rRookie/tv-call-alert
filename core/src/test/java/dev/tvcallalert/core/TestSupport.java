package dev.tvcallalert.core;

/** Shared helpers for the core tests. Not a test class. */
final class TestSupport {
    static final long T = 1794700000L;
    static final String MAC_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private TestSupport() {
    }

    /** In-memory SlotStore that also counts writes. */
    static final class FakeStore implements SlotStore {
        final SlotKeys[] keys = new SlotKeys[Limits.MAX_SLOTS + 1];
        final long[] highWater = new long[Limits.MAX_SLOTS + 1];
        int setCalls;

        @Override
        public SlotKeys keys(int slot) {
            return slot >= 1 && slot <= Limits.MAX_SLOTS ? keys[slot] : null;
        }

        @Override
        public long highWater(int slot) {
            return highWater[slot];
        }

        @Override
        public void setHighWater(int slot, long ts) {
            setCalls++;
            highWater[slot] = ts;
        }
    }

    static byte[] seed(int fill) {
        byte[] s = new byte[Limits.SEED_BYTES];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (fill + i);
        }
        return s;
    }

    /** A distinct, valid 22-character nonce for every n. */
    static String nonce(int n) {
        return String.format("%022d", n);
    }

    static String signedPart(String type, int slot, long ts, String nonce, String ref) {
        return "RT1|" + type + "|" + slot + "|" + ts + "|" + nonce + "|" + ref;
    }

    static String msg(SlotKeys keys, String type, int slot, long ts, String nonce, String ref) {
        String signed = signedPart(type, slot, ts, nonce, ref);
        return signed + "|" + Kdf.sign(keys.authKey(), signed);
    }

    static String alert(SlotKeys keys, int slot, long ts, String nonce) {
        return msg(keys, "alert", slot, ts, nonce, "-");
    }

    static String ping(SlotKeys keys, int slot, long ts, String nonce) {
        return msg(keys, "ping", slot, ts, nonce, "-");
    }

    static String cancel(SlotKeys keys, int slot, long ts, String nonce, String ref) {
        return msg(keys, "cancel", slot, ts, nonce, ref);
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
