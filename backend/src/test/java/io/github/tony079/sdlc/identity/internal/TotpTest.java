package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** RFC 6238 appendix B vectors (SHA-1 seed), truncated to 6 digits, and the ±1 step window. */
class TotpTest {

    private static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238Vectors() {
        assertThat(Totp.code(SEED, Totp.step(59))).isEqualTo("287082");
        assertThat(Totp.code(SEED, Totp.step(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(SEED, Totp.step(1111111111))).isEqualTo("050471");
        assertThat(Totp.code(SEED, Totp.step(1234567890))).isEqualTo("005924");
        assertThat(Totp.code(SEED, Totp.step(2000000000))).isEqualTo("279037");
    }

    @Test
    void verifyAcceptsOneStepEitherSideAndReturnsTheMatchedStep() {
        long now = 1234567890;
        long step = Totp.step(now);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step), now)).hasValue(step);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.verify(SEED, Totp.code(SEED, step + 2), now)).isEmpty();
        assertThat(Totp.verify(SEED, Totp.code(SEED, step - 2), now)).isEmpty();
    }

    @Test
    void verifyRejectsMalformedCodes() {
        assertThat(Totp.verify(SEED, null, 59)).isEmpty();
        assertThat(Totp.verify(SEED, "28708", 59)).isEmpty();
        assertThat(Totp.verify(SEED, "2870822", 59)).isEmpty();
        assertThat(Totp.verify(SEED, "28708a", 59)).isEmpty();
    }

    @Test
    void base32RoundTrip() {
        byte[] secret = Totp.newSecret();
        assertThat(secret).hasSize(20);
        String encoded = Totp.base32(secret);
        assertThat(encoded).matches("^[A-Z2-7]{32}$");
        assertThat(Totp.fromBase32(encoded)).isEqualTo(secret);
        assertThat(Totp.base32(SEED)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }
}
