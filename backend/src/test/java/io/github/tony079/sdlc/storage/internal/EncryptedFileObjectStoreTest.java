package io.github.tony079.sdlc.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import io.github.tony079.sdlc.storage.api.ObjectStore;
import io.github.tony079.sdlc.storage.api.ObjectStore.StoredObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Encryption at rest, integrity and key handling of the private object store (PRD FR-STO-03..05, FR-STO-10). */
class EncryptedFileObjectStoreTest {

    @TempDir
    Path root;
    private EncryptedFileObjectStore store;
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        store = new EncryptedFileObjectStore(root, Base64.getEncoder().encodeToString(key));
    }

    private static byte[] content(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ("confidential-".charAt(i % 13));
        }
        return bytes;
    }

    private byte[] read(String key) throws IOException {
        try (InputStream in = store.open(key)) {
            return in.readAllBytes();
        }
    }

    @Test
    void roundTripsMultiChunkContentWithHashAndNoPlaintextOnDisk() throws Exception {
        byte[] original = content(300_000);
        StoredObject stored = store.put(tenant, new ByteArrayInputStream(original), 1_000_000);

        assertThat(stored.key()).matches(tenant + "/[0-9a-f-]{36}");
        assertThat(stored.size()).isEqualTo(original.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        assertThat(read(stored.key())).isEqualTo(original);
        String onDisk = Files.readString(root.resolve(stored.key()), StandardCharsets.ISO_8859_1);
        assertThat(onDisk).doesNotContain("confidential");
    }

    @Test
    void emptyAndExactChunkSizesRoundTrip() throws Exception {
        for (int size : new int[] {0, 1, 65536, 65537, 131072}) {
            byte[] original = content(size);
            assertThat(read(store.put(tenant, new ByteArrayInputStream(original), 1_000_000).key())).isEqualTo(original);
        }
    }

    @Test
    void tamperingIsDetected() throws Exception {
        StoredObject stored = store.put(tenant, new ByteArrayInputStream(content(100_000)), 1_000_000);
        Path file = root.resolve(stored.key());
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);
        assertThatThrownBy(() -> read(stored.key())).isInstanceOf(IOException.class);
    }

    @Test
    void truncationIsDetected() throws Exception {
        StoredObject stored = store.put(tenant, new ByteArrayInputStream(content(200_000)), 1_000_000);
        Path file = root.resolve(stored.key());
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, 65536 + 4 + 12 + 5 + 16));
        assertThatThrownBy(() -> read(stored.key())).isInstanceOf(IOException.class);
    }

    /** An object copied under another tenant's key does not decrypt: keys are per tenant and bound to the object. */
    @Test
    void objectsAreBoundToTheirTenantAndKey() throws Exception {
        StoredObject stored = store.put(tenant, new ByteArrayInputStream(content(1000)), 1_000_000);
        UUID other = UUID.randomUUID();
        String forgedKey = other + "/" + UUID.randomUUID();
        Files.createDirectories(root.resolve(other.toString()));
        Files.copy(root.resolve(stored.key()), root.resolve(forgedKey));
        assertThatThrownBy(() -> read(forgedKey)).isInstanceOf(IOException.class);
    }

    @Test
    void oversizedContentIsRefusedAndLeavesNothing() throws Exception {
        assertThatThrownBy(() -> store.put(tenant, new ByteArrayInputStream(content(5000)), 4999))
                .isInstanceOf(ObjectStore.TooLargeException.class);
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).count()).isZero();
        }
    }

    @Test
    void keysCannotEscapeTheRoot() {
        assertThatThrownBy(() -> store.open("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.open(tenant + "/../../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncryptedFileObjectStore(root, "c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
    }
}
