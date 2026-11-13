package io.github.tony079.sdlc.identity.internal;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.internal.AccountMail.Kind;
import io.github.tony079.sdlc.identity.internal.AccountMail.Message;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;
import io.github.tony079.sdlc.tenancy.api.TenantLookup.TenantRef;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a person who is not signed in may do with an account link (FR-ORG-07/08): look an invitation up, accept it by
 * choosing a password, ask for a password-reset email, and complete a reset. Every unusable link gives the same
 * answer (no information on whether it existed, expired or was used), and a reset request always gets the same
 * answer whether or not the account exists.
 */
@Service
@ConditionalOnWebApplication
class PublicAccountService {

    record LinkDetails(String organisationName, String organisationSlug, String email, String displayName,
                       String purpose, OffsetDateTime expiresAt) {
    }

    private record Link(UUID id, UUID userId, String purpose, String email, String displayName, String userStatus) {
    }

    private final TenantLookup tenants;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder passwords;
    private final AuditWriter audit;
    private final AccountMail accountMail;

    PublicAccountService(TenantLookup tenants, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                         PasswordEncoder passwords, AuditWriter audit, AccountMail accountMail) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.passwords = passwords;
        this.audit = audit;
        this.accountMail = accountMail;
    }

    LinkDetails lookup(String organisation, String token, Kind kind) {
        TenantRef tenant = tenant(organisation).orElseThrow(PublicAccountService::unusable);
        if (!AccountTokens.wellFormed(token)) {
            throw unusable();
        }
        return TenantContext.callAs(tenant.id(), () -> tx.execute(s -> {
            Link link = find(token, kind, false).orElseThrow(PublicAccountService::unusable);
            OffsetDateTime expires = jdbc.sql("SELECT expires_at FROM app.user_invitation WHERE id = :id")
                    .param("id", link.id()).query(OffsetDateTime.class).single();
            return new LinkDetails(tenant.name(), slug(organisation), link.email(), link.displayName(), link.purpose(),
                    expires);
        }));
    }

    /** Accepts an invitation (INVITED becomes ACTIVE) or completes a password reset, in one transaction. */
    void complete(String organisation, String token, String password, Kind kind) {
        if (!AccountTokens.acceptablePassword(password)) {
            throw ApiException.validation("Choose a password of 12 to 200 characters.");
        }
        TenantRef tenant = tenant(organisation).orElseThrow(PublicAccountService::unusable);
        if (!AccountTokens.wellFormed(token)) {
            throw unusable();
        }
        String hash = passwords.encode(password);
        TenantContext.callAs(tenant.id(), () -> tx.execute(s -> {
            Link link = find(token, kind, true).orElseThrow(PublicAccountService::unusable);
            // Consuming the link first is what allows the user update below (app_user_guard).
            int consumed = jdbc.sql("""
                    UPDATE app.user_invitation SET used_at = clock_timestamp()
                    WHERE id = :id AND used_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
                    """).param("id", link.id()).update();
            if (consumed != 1) {
                throw unusable();
            }
            jdbc.sql("""
                    UPDATE app.app_user SET password_hash = :hash,
                           status = CASE WHEN status = 'INVITED' THEN 'ACTIVE' ELSE status END
                    WHERE id = :id
                    """).param("hash", hash).param("id", link.userId()).update();
            // Any other open link of the user is now pointless.
            jdbc.sql("""
                    UPDATE app.user_invitation SET revoked_at = clock_timestamp()
                    WHERE user_id = :user AND id <> :id AND used_at IS NULL AND revoked_at IS NULL
                    """).param("user", link.userId()).param("id", link.id()).update();
            audit.record(kind == Kind.INVITE ? AuditAction.USER_INVITATION_ACCEPTED : AuditAction.PASSWORD_RESET_COMPLETED,
                    "USER", link.userId(), AuditResult.SUCCESS);
            return null;
        }));
    }

    /**
     * "Forgot password": emails a reset link to an active user. Without SMTP nothing is sent (the link is never shown
     * to someone who is not signed in); the answer is the same in every case. At most one open self-service link per
     * user per 10 minutes.
     */
    void requestReset(String organisation, String email) {
        Optional<TenantRef> tenant = tenant(organisation);
        if (tenant.isEmpty() || email == null || !accountMail.configured()) {
            return;
        }
        String normalised = email.strip().toLowerCase(Locale.ROOT);
        Message message = TenantContext.callAs(tenant.get().id(), () -> tx.execute(s -> {
            Optional<Object[]> user = jdbc.sql("""
                    SELECT id, email, display_name FROM app.app_user WHERE lower(email) = :email AND status = 'ACTIVE'
                    """).param("email", normalised)
                    .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)})
                    .optional();
            if (user.isEmpty()) {
                return null;
            }
            UUID userId = (UUID) user.get()[0];
            boolean recent = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM app.user_invitation WHERE user_id = :user AND purpose = 'PASSWORD_RESET'
                                   AND created_by_user IS NULL AND created_at > clock_timestamp() - interval '10 minutes')
                    """).param("user", userId).query(Boolean.class).single();
            if (recent) {
                return null;
            }
            String token = AccountTokens.newToken();
            UUID id = jdbc.sql("""
                    INSERT INTO app.user_invitation (tenant_id, user_id, purpose, token_sha256, expires_at)
                    VALUES (:tenant, :user, 'PASSWORD_RESET', :hash, now() + interval '1 hour')
                    RETURNING id
                    """).param("tenant", tenant.get().id()).param("user", userId)
                    .param("hash", AccountTokens.hash(token)).query(UUID.class).single();
            audit.record(AuditAction.PASSWORD_RESET_REQUESTED, "USER", userId, AuditResult.SUCCESS);
            return new Message(tenant.get().id(), id, Kind.PASSWORD_RESET, tenant.get().name(), slug(organisation),
                    (String) user.get()[1], (String) user.get()[2], token);
        }));
        if (message != null) {
            accountMail.send(message);
        }
    }

    private Optional<Link> find(String token, Kind kind, boolean lock) {
        return jdbc.sql("""
                SELECT i.id, i.user_id, i.purpose, u.email, u.display_name, u.status
                FROM app.user_invitation i JOIN app.app_user u ON u.id = i.user_id
                WHERE i.token_sha256 = :hash AND i.purpose = :purpose AND i.used_at IS NULL AND i.revoked_at IS NULL
                  AND i.expires_at > clock_timestamp()
                  AND u.status = CASE WHEN i.purpose = 'INVITE' THEN 'INVITED' ELSE 'ACTIVE' END
                """ + (lock ? " FOR UPDATE OF i" : ""))
                .param("hash", AccountTokens.hash(token)).param("purpose", kind.name())
                .query((rs, n) -> new Link(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    private Optional<TenantRef> tenant(String organisation) {
        if (organisation == null || organisation.isBlank() || organisation.length() > 63) {
            return Optional.empty();
        }
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        return Optional.ofNullable(readOnly.execute(s -> tenants.findActiveBySlug(slug(organisation))))
                .flatMap(t -> t);
    }

    private static String slug(String organisation) {
        return organisation.strip().toLowerCase(Locale.ROOT);
    }

    private static ApiException unusable() {
        return new ApiException(HttpStatus.GONE, "LINK_UNUSABLE",
                "This link is no longer valid. Ask your Organisation Admin for a new one.");
    }
}
