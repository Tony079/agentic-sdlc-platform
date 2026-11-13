package io.github.tony079.sdlc.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Single-use account links (invitation, password reset): 256 random bits, stored only as a SHA-256 hash. */
final class AccountTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private AccountTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** True when the value could be a token at all; anything else is refused before a database lookup. */
    static boolean wellFormed(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Password rule for organisation users (FR-ORG-07): 12 to 200 characters, not only whitespace. */
    static boolean acceptablePassword(String password) {
        return password != null && password.length() >= 12 && password.length() <= 200 && !password.isBlank();
    }
}
