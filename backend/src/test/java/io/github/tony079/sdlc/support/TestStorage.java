package io.github.tony079.sdlc.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/** One private storage root and a random encryption key per test JVM, shared by the api and worker contexts. */
public final class TestStorage {

    public static final Path ROOT;
    public static final String KEY;

    static {
        try {
            ROOT = Files.createTempDirectory("sdlc-it-storage-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        KEY = Base64.getEncoder().encodeToString(key);
    }

    private TestStorage() {
    }
}
