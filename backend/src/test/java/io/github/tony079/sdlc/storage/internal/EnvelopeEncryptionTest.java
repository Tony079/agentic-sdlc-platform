package io.github.tony079.sdlc.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O-12/C-13: envelope encryption (SDE2 with a wrapped per-object data key), legacy SDE1 objects still readable,
 * tenant-scoped access, and fail-closed refusal of an unimplemented production KMS.
 */
class EnvelopeEncryptionTest {

    @TempDir
    Path root;
    private final byte[] master = random(32);
    private final UUID tenant = UUID.randomUUID();

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private EncryptedFileObjectStore store() throws IOException {
        return new EncryptedFileObjectStore(root, Base64.getEncoder().encodeToString(master));
    }

    @Test
    void newObjectsUseAWrappedPerObjectDataKey() throws Exception {
        EncryptedFileObjectStore store = store();
        String a = store.put(tenant, new ByteArrayInputStream("same plaintext".getBytes()), 1000).key();
        String b = store.put(tenant, new ByteArrayInputStream("same plaintext".getBytes()), 1000).key();
        byte[] fileA = Files.readAllBytes(root.resolve(a));
        assertThat(new String(fileA, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("SDE2");
        assertThat(new String(fileA, StandardCharsets.ISO_8859_1)).contains(LocalKeyManagement.KEY_ID)
                .doesNotContain("same plaintext");
        // Different data keys: the wrapped keys differ.
        assertThat(java.util.Arrays.copyOfRange(fileA, 0, 60))
                .isNotEqualTo(java.util.Arrays.copyOfRange(Files.readAllBytes(root.resolve(b)), 0, 60));
        try (InputStream in = store.open(a)) {
            assertThat(new String(in.readAllBytes())).isEqualTo("same plaintext");
        }
        // Tampering with the wrapped key fails the unwrap.
        fileA[14] ^= 1;
        Files.write(root.resolve(a), fileA);
        assertThatThrownBy(() -> store.open(a).readAllBytes()).isInstanceOf(IOException.class);
    }

    @Test
    void legacySde1ObjectsRemainReadable() throws Exception {
        String key = tenant + "/" + UUID.randomUUID();
        byte[] plaintext = "written before envelope encryption".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve(key).getParent());
        Files.write(root.resolve(key), legacy(key, plaintext));
        try (InputStream in = store().open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(plaintext);
        }
    }

    @Test
    void anotherTenantsObjectCannotBeOpenedOrDeletedInsideATenantContext() throws Exception {
        EncryptedFileObjectStore store = store();
        String key = store.put(tenant, new ByteArrayInputStream("tenant data".getBytes()), 1000).key();
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> TenantContext.callAs(other, () -> {
            try {
                return store.open(key);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        })).hasCauseInstanceOf(java.io.FileNotFoundException.class);
        TenantContext.callAs(other, () -> {
            assertThatThrownBy(() -> store.delete(key)).isInstanceOf(java.io.FileNotFoundException.class);
            return null;
        });
        assertThat(Files.exists(root.resolve(key))).isTrue();
        try (InputStream in = TenantContext.callAs(tenant, () -> {
            try {
                return store.open(key);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        })) {
            assertThat(new String(in.readAllBytes())).isEqualTo("tenant data");
        }
    }

    @Test
    void anUnimplementedProductionKmsRefusesToStart() {
        assertThatThrownBy(() -> new EncryptedFileObjectStore(root, Base64.getEncoder().encodeToString(master),
                "aws-kms")).isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing to start");
    }

    /** The SDE1 format exactly as the earlier implementation wrote it (one final chunk). */
    private byte[] legacy(String key, byte[] plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        byte[] tenantKey = mac.doFinal(("sdlc-object-key-v1:" + tenant).getBytes(StandardCharsets.US_ASCII));
        byte[] nonce = random(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] chunkNonce = nonce.clone();
        ByteBuffer.wrap(chunkNonce, 4, 8).putLong(ByteBuffer.wrap(nonce, 4, 8).getLong() ^ 0L);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tenantKey, "AES"), new GCMParameterSpec(128, chunkNonce));
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);
        cipher.updateAAD(ByteBuffer.allocate(keyBytes.length + 9).put(keyBytes).putLong(0).put((byte) 1).array());
        byte[] sealed = cipher.doFinal(plaintext);
        var bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write("SDE1".getBytes(StandardCharsets.US_ASCII));
            out.write(nonce);
            out.writeInt(sealed.length);
            out.writeByte(1);
            out.write(sealed);
        }
        return bytes.toByteArray();
    }
}
