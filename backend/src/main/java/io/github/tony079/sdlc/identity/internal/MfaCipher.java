package io.github.tony079.sdlc.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Encrypts staff TOTP secrets at rest (owner decision O-11): AES-256-GCM with a key from {@code SDLC_MFA_KEY} (32 bytes,
 * base64), never stored in the database. The staff id is bound as associated data, so a ciphertext copied to another
 * staff row does not decrypt. Used by the API and the provisioning process only.
 */
@Component
@Profile({"!migrate & !worker & !retention"})
class MfaCipher {

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    MfaCipher(@Value("${sdlc.staff.mfa-key:}") String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sdlc.staff.mfa-key must be base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("sdlc.staff.mfa-key must be 32 bytes, base64 (openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    String encrypt(UUID staffId, byte[] secret) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(staffId.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = cipher.doFinal(secret);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    byte[] decrypt(UUID staffId, String encoded) {
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
            cipher.updateAAD(staffId.toString().getBytes(StandardCharsets.US_ASCII));
            return cipher.doFinal(data, 12, data.length - 12);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("MFA secret cannot be decrypted");
        }
    }
}
