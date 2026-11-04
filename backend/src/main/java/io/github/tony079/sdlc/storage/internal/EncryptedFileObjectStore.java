package io.github.tony079.sdlc.storage.internal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.tony079.sdlc.storage.api.ObjectStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Private, encrypted file-system object store (decision D-M4-01). Used for local development, CI and tests; the
 * production object store is owner decision O-12 and plugs in behind {@link ObjectStore}.
 *
 * <ul>
 *   <li>Keys are {@code <tenant-uuid>/<random-uuid>}: opaque, never a file name (FR-STO-04), resolved strictly under
 *       the root directory (no path traversal).</li>
 *   <li>Encryption at rest (FR-STO-05): AES-256-GCM per 64 KiB chunk, with a per-tenant key derived from the master
 *       key by HMAC-SHA256, a random per-object nonce prefix, and the object key, chunk index and a final-chunk
 *       flag as associated data, so chunks cannot be reordered, swapped between objects or tenants, or truncated.</li>
 *   <li>Writes go to a temporary file and are moved into place atomically; partial objects never become visible.</li>
 * </ul>
 */
@Profile("!migrate")
@Component
class EncryptedFileObjectStore implements ObjectStore {

    private static final byte[] MAGIC = "SDE1".getBytes(StandardCharsets.US_ASCII);
    private static final int CHUNK = 64 * 1024;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY = Pattern.compile("^[0-9a-f-]{36}/[0-9a-f-]{36}$");

    private final Path root;
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    EncryptedFileObjectStore(@Value("${sdlc.storage.root}") Path root,
                             @Value("${sdlc.storage.encryption-key}") String encodedKey) throws IOException {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedKey.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sdlc.storage.encryption-key must be base64");
        }
        if (key.length != 32) {
            throw new IllegalStateException("sdlc.storage.encryption-key must be 32 bytes (base64), e.g. openssl rand -base64 32");
        }
        this.masterKey = key;
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public StoredObject put(UUID tenantId, InputStream content, long maxBytes) throws IOException {
        String key = tenantId + "/" + UUID.randomUUID();
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            restrictPermissions(temp);
            MessageDigest sha256 = sha256();
            long size = 0;
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.write(MAGIC);
                out.write(nonce);
                byte[] buffer = new byte[CHUNK];
                byte[] pending = null;
                int pendingLength = 0;
                long index = 0;
                int read;
                while ((read = content.readNBytes(buffer, 0, CHUNK)) > 0) {
                    size += read;
                    if (size > maxBytes) {
                        throw new TooLargeException();
                    }
                    sha256.update(buffer, 0, read);
                    if (pending != null) {
                        writeChunk(out, tenantId, key, nonce, index++, pending, pendingLength, false);
                    }
                    pending = Arrays.copyOf(buffer, read);
                    pendingLength = read;
                }
                writeChunk(out, tenantId, key, nonce, index, pending == null ? new byte[0] : pending, pendingLength, true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            return new StoredObject(key, HexFormat.of().formatHex(sha256.digest()), size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        Path path = resolve(key);
        DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)));
        byte[] magic = in.readNBytes(4);
        byte[] nonce = in.readNBytes(12);
        if (!Arrays.equals(magic, MAGIC) || nonce.length != 12) {
            in.close();
            throw new IOException("Not an encrypted object");
        }
        UUID tenant = UUID.fromString(key.substring(0, 36));
        return new DecryptingStream(in, tenant, key, nonce);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    private void writeChunk(DataOutputStream out, UUID tenant, String key, byte[] nonce, long index, byte[] data,
                            int length, boolean last) throws IOException {
        byte[] sealed = crypt(Cipher.ENCRYPT_MODE, tenant, key, nonce, index, last, data, length);
        out.writeInt(sealed.length);
        out.writeByte(last ? 1 : 0);
        out.write(sealed);
    }

    private byte[] crypt(int mode, UUID tenant, String key, byte[] nonce, long index, boolean last, byte[] data,
                         int length) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] chunkNonce = nonce.clone();
            ByteBuffer.wrap(chunkNonce, 4, 8).putLong(ByteBuffer.wrap(nonce, 4, 8).getLong() ^ index);
            cipher.init(mode, new SecretKeySpec(tenantKey(tenant), "AES"), new GCMParameterSpec(TAG_BITS, chunkNonce));
            cipher.updateAAD(associatedData(key, index, last));
            return cipher.doFinal(data, 0, length);
        } catch (GeneralSecurityException e) {
            throw new IOException("Object integrity check failed", e);
        }
    }

    private byte[] tenantKey(UUID tenant) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
        return mac.doFinal(("sdlc-object-key-v1:" + tenant).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] associatedData(String key, long index, boolean last) {
        byte[] keyBytes = key.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(keyBytes.length + 9).put(keyBytes).putLong(index).put((byte) (last ? 1 : 0)).array();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX file systems: rely on the directory permissions.
        }
    }

    /** Decrypts chunk by chunk; fails on tampering, reordering or truncation (missing final chunk). */
    private final class DecryptingStream extends InputStream {
        private final DataInputStream in;
        private final UUID tenant;
        private final String key;
        private final byte[] nonce;
        private byte[] current = new byte[0];
        private int position;
        private long index;
        private boolean finished;

        DecryptingStream(DataInputStream in, UUID tenant, String key, byte[] nonce) {
            this.in = in;
            this.tenant = tenant;
            this.key = key;
            this.nonce = nonce;
        }

        private boolean fill() throws IOException {
            while (position >= current.length) {
                if (finished) {
                    return false;
                }
                int length;
                boolean last;
                try {
                    length = in.readInt();
                    last = in.readByte() == 1;
                } catch (EOFException e) {
                    throw new IOException("Object is truncated");
                }
                if (length < 16 || length > CHUNK + 16) {
                    throw new IOException("Object is corrupt");
                }
                byte[] sealed = in.readNBytes(length);
                if (sealed.length != length) {
                    throw new IOException("Object is truncated");
                }
                current = crypt(Cipher.DECRYPT_MODE, tenant, key, nonce, index++, last, sealed, sealed.length);
                position = 0;
                finished = last;
                if (last && in.read() != -1) {
                    throw new IOException("Object has trailing data");
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            return fill() ? current[position++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, current.length - position);
            System.arraycopy(current, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
