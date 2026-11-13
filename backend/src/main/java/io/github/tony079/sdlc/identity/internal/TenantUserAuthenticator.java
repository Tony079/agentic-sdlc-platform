package io.github.tony079.sdlc.identity.internal;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.identity.internal.AppUserRepository.UserRecord;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.github.tony079.sdlc.tenancy.api.TenantLookup.TenantRef;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies organisation + email + password (decision D-M1-01).
 *
 * <p>The organisation only selects which tenant's users are searched before authentication. After login
 * the tenant comes from the session (PRD FR-AUTH-06). Every failure path performs one password-hash
 * verification, so unknown organisations and unknown users take about as long as a wrong password and
 * produce the same result (no account enumeration).
 */
@Component
class TenantUserAuthenticator {

    private final TenantLookup tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate readOnlyTx;
    private final String dummyHash;

    TenantUserAuthenticator(TenantLookup tenants, AppUserRepository users, PasswordEncoder passwordEncoder,
                            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    Optional<CurrentUser> authenticate(String organisation, String email, String password) {
        String slug = organisation.trim().toLowerCase(Locale.ROOT);
        Optional<TenantRef> tenant = readOnlyTx.execute(status -> tenants.findActiveBySlug(slug));
        if (tenant == null || tenant.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            return Optional.empty();
        }
        TenantRef ref = tenant.get();
        // The tenant scope is applied when the transaction begins, so it must wrap the transaction.
        Optional<UserRecord> user = TenantContext.callAs(ref.id(),
                () -> readOnlyTx.execute(status -> users.findByEmail(email.trim())));
        UserRecord record = user == null ? null : user.orElse(null);
        // Invited users have no password yet: compare against the dummy hash so timing and result stay uniform.
        boolean passwordMatches = passwordEncoder.matches(password,
                record == null || record.passwordHash() == null ? dummyHash : record.passwordHash());
        if (record == null || !passwordMatches || !record.active()) {
            return Optional.empty();
        }
        return Optional.of(new CurrentUser(record.id(), ref.id(), ref.name(), record.email(), record.displayName(),
                record.role()));
    }
}
