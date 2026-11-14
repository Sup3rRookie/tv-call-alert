package dev.tvcallalert.core;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 helpers and per-slot key derivation from the pairing seed. */
public final class Kdf {
    private Kdf() {
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory on every JVM and Android version.
            throw new IllegalStateException(e);
        }
    }

    /** Signature text for a signed part: base64url of HMAC-SHA256, 43 characters. */
    public static String sign(byte[] key, String signedPart) {
        return Codec.base64Url(hmac(key, Codec.ascii(signedPart)));
    }

    /** Constant-time check of a signature. */
    public static boolean verify(byte[] key, String signedPart, String mac) {
        byte[] expected = Codec.ascii(sign(key, signedPart));
        return MessageDigest.isEqual(expected, Codec.ascii(mac));
    }

    public static SlotKeys derive(byte[] seed) {
        if (seed == null || seed.length != Limits.SEED_BYTES) {
            throw new IllegalArgumentException("seed must be 16 bytes");
        }
        byte[] auth = hmac(seed, Codec.ascii("rt1/auth"));
        byte[] ack = hmac(seed, Codec.ascii("rt1/ack"));
        String down = "rt" + Codec.hex(hmac(seed, Codec.ascii("rt1/down")))
                .substring(0, Limits.TOPIC_HEX_CHARS);
        String up = "rt" + Codec.hex(hmac(seed, Codec.ascii("rt1/up")))
                .substring(0, Limits.TOPIC_HEX_CHARS);
        return new SlotKeys(auth, ack, down, up);
    }
}
