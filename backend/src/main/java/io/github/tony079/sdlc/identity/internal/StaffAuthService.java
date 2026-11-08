package io.github.tony079.sdlc.identity.internal;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import io.github.tony079.sdlc.identity.internal.StaffRepository.Assignment;
import io.github.tony079.sdlc.identity.internal.StaffRepository.StaffRecord;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff password and TOTP checks for the API (owner decisions O-06, O-11). Staff tables are global (no RLS); the API
 * login can read them and change only the MFA columns of staff_user, under the 0017 trigger rules.
 */
@Service
@Profile({"!migrate & !worker & !provision & !retention"})
class StaffAuthService {

    private final StaffRepository staff;
    private final PasswordEncoder passwordEncoder;
    private final MfaCipher cipher;
    private final Clock clock;
    private final String dummyHash;

    @Autowired
    StaffAuthService(StaffRepository staff, PasswordEncoder passwordEncoder, MfaCipher cipher) {
        this(staff, passwordEncoder, cipher, Clock.systemUTC());
    }

    StaffAuthService(StaffRepository staff, PasswordEncoder passwordEncoder, MfaCipher cipher, Clock clock) {
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Constant work for unknown, wrong-password and disabled accounts; same empty result for each. */
    @Transactional(readOnly = true)
    public Optional<StaffRecord> authenticate(String email, String password) {
        Optional<StaffRecord> found = staff.findByEmail(email.trim());
        StaffRecord record = found.orElse(null);
        boolean matches = passwordEncoder.matches(password, record == null ? dummyHash : record.passwordHash());
        if (record == null || !matches || !record.active()) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Transactional(readOnly = true)
    public Optional<StaffRecord> find(UUID staffId) {
        return staff.findById(staffId);
    }

    /** A fresh secret, stored encrypted and not yet enrolled; empty if enrolled or outside the enrolment window. */
    @Transactional
    public Optional<byte[]> startEnrolment(UUID staffId) {
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() != null
                || !record.get().enrolmentOpen()) {
            return Optional.empty();
        }
        byte[] secret = Totp.newSecret();
        return staff.storePendingSecret(staffId, cipher.encrypt(staffId, secret)) ? Optional.of(secret) : Optional.empty();
    }

    @Transactional
    public boolean confirmEnrolment(UUID staffId, String code) {
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() != null
                || record.get().mfaSecretEnc() == null) {
            return false;
        }
        OptionalLong step = Totp.verify(cipher.decrypt(staffId, record.get().mfaSecretEnc()), code,
                clock.instant().getEpochSecond());
        return step.isPresent() && staff.confirmEnrolment(staffId, step.getAsLong());
    }

    /** True only for a valid code whose step was not used before (replay guard is atomic in the database). */
    @Transactional
    public boolean verify(UUID staffId, String code) {
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active() || record.get().mfaEnrolledAt() == null) {
            return false;
        }
        OptionalLong step = Totp.verify(cipher.decrypt(staffId, record.get().mfaSecretEnc()), code,
                clock.instant().getEpochSecond());
        return step.isPresent() && staff.consumeStep(staffId, step.getAsLong());
    }

    @Transactional(readOnly = true)
    public List<Assignment> assignments(UUID staffId) {
        return staff.activeAssignments(staffId);
    }

    @Transactional(readOnly = true)
    public List<String> capabilities(UUID staffId) {
        return staff.capabilities(staffId);
    }

    /** The assignment for this tenant if the staff member is ACTIVE and actively assigned to the ACTIVE tenant. */
    @Transactional(readOnly = true)
    public Optional<Assignment> assignment(UUID staffId, UUID tenantId) {
        Optional<StaffRecord> record = staff.findById(staffId);
        if (record.isEmpty() || !record.get().active()) {
            return Optional.empty();
        }
        return staff.activeAssignments(staffId).stream().filter(a -> a.tenantId().equals(tenantId)).findFirst();
    }
}
