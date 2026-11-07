package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MfaCipherTest {

    private final MfaCipher cipher = new MfaCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void roundTripIsBoundToTheStaffId() {
        UUID staff = UUID.randomUUID();
        byte[] secret = Totp.newSecret();
        String sealed = cipher.encrypt(staff, secret);
        assertThat(sealed).doesNotContain(Totp.base32(secret));
        assertThat(cipher.decrypt(staff, sealed)).isEqualTo(secret);
        assertThat(cipher.encrypt(staff, secret)).isNotEqualTo(sealed);
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe32BytesBase64() {
        assertThatThrownBy(() -> new MfaCipher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MfaCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MfaCipher("not base64!")).isInstanceOf(IllegalStateException.class);
    }
}
