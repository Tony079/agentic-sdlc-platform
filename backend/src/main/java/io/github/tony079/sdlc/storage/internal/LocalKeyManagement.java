package io.github.tony079.sdlc.storage.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.github.tony079.sdlc.storage.api.KeyManagement;

/**
 * Development and test key management (NOT a production KMS): key-encryption keys are derived per tenant from the
 * configured master key; data keys are wrapped with AES-256-GCM bound to key id, tenant and object key.
 */
class LocalKeyManagement implements KeyManagement {

    static final String KEY_ID = "local:v1";
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    LocalKeyManagement(byte[] masterKey) {
        this.masterKey = masterKey.clone();
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public DataKey newDataKey(UUID tenantId, String objectKey) throws IOException {
        byte[] key = new byte[32];
        random.nextBytes(key);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek(tenantId), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(KEY_ID, tenantId, objectKey));
            byte[] sealed = cipher.doFinal(key);
            return new DataKey(KEY_ID, key, ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IOException("data key could not be wrapped", e);
        }
    }

    @Override
    public byte[] unwrap(String keyId, byte[] wrapped, UUID tenantId, String objectKey) throws IOException {
        if (!KEY_ID.equals(keyId) || wrapped.length < 12 + 16) {
            throw new IOException("unknown data key");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek(tenantId), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(wrapped, 0, 12)));
            cipher.updateAAD(aad(keyId, tenantId, objectKey));
            return cipher.doFinal(wrapped, 12, wrapped.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IOException("data key could not be unwrapped", e);
        }
    }

    private byte[] kek(UUID tenantId) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
        return mac.doFinal(("sdlc-kek-v1:" + tenantId).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] aad(String keyId, UUID tenantId, String objectKey) {
        return (keyId + "|" + tenantId + "|" + objectKey).getBytes(StandardCharsets.US_ASCII);
    }
}
