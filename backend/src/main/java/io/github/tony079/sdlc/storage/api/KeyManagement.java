package io.github.tony079.sdlc.storage.api;

import java.io.IOException;
import java.util.UUID;

/**
 * Envelope-encryption boundary (owner decision O-12/C-13, 2026-11-08). Every stored object gets its own random data key;
 * the key-management service wraps it with a key-encryption key that never leaves the service, bound to the tenant and
 * object key. A managed KMS adapter implements this interface in production; the local adapter is for development and
 * tests only and is refused in production (fail closed).
 */
public interface KeyManagement {

    /** Provider name recorded with the wrapped key, e.g. {@code local}. */
    String provider();

    DataKey newDataKey(UUID tenantId, String objectKey) throws IOException;

    /** Unwraps a data key; fails if the tenant, object key or key id do not match what it was wrapped for. */
    byte[] unwrap(String keyId, byte[] wrapped, UUID tenantId, String objectKey) throws IOException;

    record DataKey(String keyId, byte[] plaintext, byte[] wrapped) {
    }
}
