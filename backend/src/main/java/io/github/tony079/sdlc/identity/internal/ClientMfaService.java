package io.github.tony079.sdlc.identity.internal;

import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TOTP for Organisation Admins (Garuda GD-08), the same scheme as staff (O-11): an encrypted secret per user, a
 * replay counter that only moves forward, and enrolment confirmed by a first valid code. Runs inside the user's tenant
 * (row-level security) before the session is fully authenticated.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
class ClientMfaService {

    record MfaState(String secretEnc, boolean enrolled, Long lastStep) {
    }

    private final JdbcClient jdbc;
    private final MfaCipher cipher;
    private final TransactionTemplate tx;
    private final Clock clock;

    ClientMfaService(JdbcClient jdbc, MfaCipher cipher, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = Clock.systemUTC();
    }

    boolean enrolled(UUID tenant, UUID user) {
        return state(tenant, user).map(MfaState::enrolled).orElse(false);
    }

    /** Starts (or restarts) enrolment: stores a new, unconfirmed secret and returns it to show once. */
    byte[] startEnrolment(UUID tenant, UUID user) {
        byte[] secret = Totp.newSecret();
        String sealed = cipher.encrypt(user, secret);
        Integer updated = TenantContext.callAs(tenant, () -> tx.execute(s -> jdbc.sql("""
                UPDATE app.app_user SET mfa_secret_enc = :secret WHERE id = :id AND mfa_enrolled_at IS NULL
                """).param("secret", sealed).param("id", user).update()));
        if (updated == null || updated != 1) {
            throw new IllegalStateException("MFA is already enrolled");
        }
        return secret;
    }

    /** Confirms enrolment with a first valid code; false when the code is wrong or reused. */
    boolean confirmEnrolment(UUID tenant, UUID user, String code) {
        return Boolean.TRUE.equals(TenantContext.callAs(tenant, () -> tx.execute(s -> {
            MfaState state = read(user).orElse(null);
            if (state == null || state.secretEnc() == null || state.enrolled()) {
                return false;
            }
            OptionalLong step = Totp.verify(cipher.decrypt(user, state.secretEnc()), code, clock.instant().getEpochSecond());
            return step.isPresent() && jdbc.sql("""
                    UPDATE app.app_user SET mfa_enrolled_at = clock_timestamp(), totp_last_step = :step
                    WHERE id = :id AND mfa_enrolled_at IS NULL AND (totp_last_step IS NULL OR totp_last_step < :step)
                    """).param("step", step.getAsLong()).param("id", user).update() == 1;
        })));
    }

    /** Verifies a code at sign-in, consuming its time step (no replay). */
    boolean verify(UUID tenant, UUID user, String code) {
        return Boolean.TRUE.equals(TenantContext.callAs(tenant, () -> tx.execute(s -> {
            MfaState state = read(user).orElse(null);
            if (state == null || !state.enrolled()) {
                return false;
            }
            OptionalLong step = Totp.verify(cipher.decrypt(user, state.secretEnc()), code, clock.instant().getEpochSecond());
            return step.isPresent() && jdbc.sql("""
                    UPDATE app.app_user SET totp_last_step = :step
                    WHERE id = :id AND (totp_last_step IS NULL OR totp_last_step < :step)
                    """).param("step", step.getAsLong()).param("id", user).update() == 1;
        })));
    }

    private Optional<MfaState> state(UUID tenant, UUID user) {
        return TenantContext.callAs(tenant, () -> tx.execute(s -> read(user)));
    }

    private Optional<MfaState> read(UUID user) {
        return jdbc.sql("SELECT mfa_secret_enc, mfa_enrolled_at IS NOT NULL, totp_last_step FROM app.app_user WHERE id = :id")
                .param("id", user)
                .query((rs, n) -> new MfaState(rs.getString(1), rs.getBoolean(2), (Long) rs.getObject(3)))
                .optional();
    }
}
