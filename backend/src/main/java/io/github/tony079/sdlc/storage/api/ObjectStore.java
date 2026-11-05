package io.github.tony079.sdlc.storage.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Private object storage (PRD FR-STO-01..10). Keys are opaque and never derived from file names; content is
 * encrypted at rest; there is no public access path. Storage credentials never leave this module (FR-STO-09).
 */
public interface ObjectStore {

    /**
     * Streams {@code content} into a new object of {@code tenantId}, computing its SHA-256 on the way. Never holds
     * the whole content in memory. Aborts, leaving nothing behind, once more than {@code maxBytes} arrive.
     */
    StoredObject put(UUID tenantId, InputStream content, long maxBytes) throws IOException;

    /** Opens an object for reading (decrypted, integrity-checked while reading). The caller closes the stream. */
    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;

    record StoredObject(String key, String sha256, long size) {
    }

    /** More than the allowed number of bytes were sent. */
    class TooLargeException extends IOException {
        public TooLargeException() {
            super("Object exceeds the size limit");
        }
    }
}
