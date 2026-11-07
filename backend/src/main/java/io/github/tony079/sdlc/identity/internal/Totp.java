package io.github.tony079.sdlc.identity.internal;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30-second step, 6 digits), as used by common authenticator apps (owner decision O-11).
 * Verification accepts the current step and one step either side, and returns the matched step so the caller can
 * reject reuse (replay).
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new 160-bit secret. */
    static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static long step(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The matched step if {@code code} is valid at {@code epochSeconds} (±1 step), compared in constant time. */
    static OptionalLong verify(byte[] secret, String code, long epochSeconds) {
        if (code == null || !code.matches("^[0-9]{6}$")) {
            return OptionalLong.empty();
        }
        long now = step(epochSeconds);
        long matched = -1;
        for (long candidate = now - 1; candidate <= now + 1; candidate++) {
            if (MessageDigest.isEqual(code(secret, candidate).getBytes(), code.getBytes()) && matched < 0) {
                matched = candidate;
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    static byte[] fromBase32(String text) {
        String clean = text.replace("=", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }
}
