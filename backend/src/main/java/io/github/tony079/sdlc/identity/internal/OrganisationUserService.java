package io.github.tony079.sdlc.identity.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.ClientUsers;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.identity.api.CurrentUser.TenantRole;
import io.github.tony079.sdlc.identity.internal.AccountMail.Kind;
import io.github.tony079.sdlc.identity.internal.AccountMail.Message;
import io.github.tony079.sdlc.project.api.ProjectAdministration;
import io.github.tony079.sdlc.project.api.ProjectAdministration.Membership;
import io.github.tony079.sdlc.project.api.ProjectRole;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User administration by an Organisation Admin (FR-ORG-04..08): invite users into projects, resend or revoke
 * invitations, change the organisation role, deactivate and reactivate, reset MFA, and send a password-reset link.
 * Every rule is enforced again by the database (app_user_guard, user_invitation_guard, project_member_guard).
 */
@Service
@ConditionalOnWebApplication
class OrganisationUserService {

    static final Pattern EMAIL = Pattern.compile("^[^@\\s<>\"]{1,64}@[^@\\s<>\"]+\\.[^@\\s<>\"]{2,}$");

    record UserSummary(UUID id, String email, String displayName, TenantRole role, String status, boolean mfaEnrolled,
                       OffsetDateTime createdAt, OffsetDateTime activatedAt, Invitation invitation,
                       List<Membership> memberships) {
    }

    record Invitation(UUID id, String purpose, OffsetDateTime createdAt, OffsetDateTime expiresAt, String emailStatus) {
    }

    record OrganisationSummary(UUID id, String name, String slug, int activeUsers, int invitedUsers,
                               int deactivatedUsers, int organisationAdmins, boolean emailConfigured) {
    }

    record MembershipRequest(UUID projectId, ProjectRole role) {
    }

    /** Result of an action that creates a link: the link is always returned so the admin can copy it (GD-04). */
    record LinkResult(UUID userId, UUID invitationId, String link, String emailStatus, OffsetDateTime expiresAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditWriter audit;
    private final ProjectAdministration projects;
    private final AccountMail accountMail;

    OrganisationUserService(JdbcClient jdbc, PlatformTransactionManager transactionManager, AuditWriter audit,
                            ProjectAdministration projects, AccountMail accountMail) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.projects = projects;
        this.accountMail = accountMail;
    }

    OrganisationSummary summary() {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        return tx.execute(s -> jdbc.sql("""
                SELECT t.id, t.name, t.slug,
                       count(*) FILTER (WHERE u.status = 'ACTIVE'),
                       count(*) FILTER (WHERE u.status = 'INVITED'),
                       count(*) FILTER (WHERE u.status = 'DISABLED'),
                       count(*) FILTER (WHERE u.status = 'ACTIVE' AND u.role = 'ORG_ADMIN')
                FROM app.tenant t LEFT JOIN app.app_user u ON u.tenant_id = t.id
                WHERE t.id = :tenant GROUP BY t.id, t.name, t.slug
                """).param("tenant", admin.tenantId())
                .query((rs, n) -> new OrganisationSummary(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        accountMail.configured()))
                .single());
    }

    List<UserSummary> list() {
        ClientUsers.requireOrgAdmin();
        return tx.execute(s -> jdbc.sql("""
                SELECT u.id, u.email, u.display_name, u.role, u.status, u.mfa_enrolled_at IS NOT NULL,
                       u.created_at, u.activated_at, i.id, i.purpose, i.created_at, i.expires_at, i.email_status
                FROM app.app_user u
                LEFT JOIN LATERAL (SELECT * FROM app.user_invitation x
                                   WHERE x.user_id = u.id AND x.used_at IS NULL AND x.revoked_at IS NULL
                                     AND x.expires_at > clock_timestamp()
                                   ORDER BY x.created_at DESC LIMIT 1) i ON true
                ORDER BY u.status = 'DISABLED', lower(u.display_name), lower(u.email)
                """).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        TenantRole.valueOf(rs.getString(4)), rs.getString(5), rs.getBoolean(6),
                        rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class),
                        rs.getObject(9) == null ? null : new Invitation(rs.getObject(9, UUID.class), rs.getString(10),
                                rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class),
                                rs.getString(13))})
                .list().stream()
                .map(r -> new UserSummary((UUID) r[0], (String) r[1], (String) r[2], (TenantRole) r[3], (String) r[4],
                        (Boolean) r[5], (OffsetDateTime) r[6], (OffsetDateTime) r[7], (Invitation) r[8],
                        projects.membershipsOf((UUID) r[0])))
                .toList());
    }

    /** FR-ORG-05: creates an INVITED user, adds the project memberships and issues a 72-hour invitation link. */
    LinkResult invite(String email, String displayName, TenantRole role, List<MembershipRequest> memberships) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        String normalised = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String name = displayName == null ? "" : displayName.strip();
        if (!EMAIL.matcher(normalised).matches() || normalised.length() > 320) {
            throw ApiException.validation("Enter a valid email address.");
        }
        if (name.isEmpty() || name.length() > 200) {
            throw ApiException.validation("Enter the person's name (up to 200 characters).");
        }
        if (role == null) {
            throw ApiException.validation("Choose an organisation role.");
        }
        List<MembershipRequest> wanted = memberships == null ? List.of() : memberships;
        if (wanted.stream().anyMatch(m -> m.projectId() == null || m.role() == null)) {
            throw ApiException.validation("Each project needs a project role.");
        }
        if (wanted.stream().map(MembershipRequest::projectId).distinct().count() != wanted.size()) {
            throw ApiException.validation("A project is listed twice.");
        }
        Message message;
        try {
            message = tx.execute(s -> {
                UUID userId = jdbc.sql("""
                        INSERT INTO app.app_user (tenant_id, email, display_name, role, status)
                        VALUES (:tenant, :email, :name, :role, 'INVITED') RETURNING id
                        """).param("tenant", admin.tenantId()).param("email", normalised).param("name", name)
                        .param("role", role.name()).query(UUID.class).single();
                for (MembershipRequest m : wanted) {
                    projects.addMember(m.projectId(), userId, m.role());
                }
                Message created = issue(admin, userId, normalised, name, Kind.INVITE);
                audit.record(AuditAction.USER_INVITED, "USER", userId, AuditResult.SUCCESS);
                return created;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("USER_EXISTS", "A user with this email already exists in the organisation.");
        }
        return deliver(message);
    }

    /** Revokes any open invitation of an INVITED user and issues a new one. */
    LinkResult resendInvitation(UUID userId) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        Message message = tx.execute(s -> {
            UserRow user = user(userId);
            if (!"INVITED".equals(user.status())) {
                throw ApiException.conflict("USER_NOT_INVITED", "This user has already accepted the invitation.");
            }
            revokeOpen(userId);
            Message created = issue(admin, userId, user.email(), user.displayName(), Kind.INVITE);
            audit.record(AuditAction.USER_INVITATION_RESENT, "USER", userId, AuditResult.SUCCESS);
            return created;
        });
        return deliver(message);
    }

    void revokeInvitation(UUID userId) {
        ClientUsers.requireOrgAdmin();
        tx.executeWithoutResult(s -> {
            user(userId);
            if (revokeOpen(userId) == 0) {
                throw ApiException.conflict("NO_OPEN_INVITATION", "There is no open link for this user.");
            }
            audit.record(AuditAction.USER_INVITATION_REVOKED, "USER", userId, AuditResult.SUCCESS);
        });
    }

    void changeRole(UUID userId, TenantRole role) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        if (role == null) {
            throw ApiException.validation("Choose an organisation role.");
        }
        notSelf(admin, userId);
        guarded(() -> tx.executeWithoutResult(s -> {
            UserRow user = user(userId);
            if (user.role() == role) {
                return;
            }
            jdbc.sql("UPDATE app.app_user SET role = :role WHERE id = :id").param("role", role.name())
                    .param("id", userId).update();
            audit.record(AuditAction.USER_ROLE_CHANGED, "USER", userId, AuditResult.SUCCESS);
        }));
    }

    /** Deactivation ends the user's sessions on their next request (TenantStatusFilter) and revokes open links. */
    void deactivate(UUID userId) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        notSelf(admin, userId);
        guarded(() -> tx.executeWithoutResult(s -> {
            UserRow user = user(userId);
            if ("DISABLED".equals(user.status())) {
                return;
            }
            if ("INVITED".equals(user.status())) {
                throw ApiException.conflict("USER_INVITED", "Revoke the invitation instead.");
            }
            jdbc.sql("UPDATE app.app_user SET status = 'DISABLED' WHERE id = :id").param("id", userId).update();
            revokeOpen(userId);
            audit.record(AuditAction.USER_DEACTIVATED, "USER", userId, AuditResult.SUCCESS);
        }));
    }

    void reactivate(UUID userId) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        notSelf(admin, userId);
        guarded(() -> tx.executeWithoutResult(s -> {
            UserRow user = user(userId);
            if (!"DISABLED".equals(user.status())) {
                throw ApiException.conflict("USER_NOT_DEACTIVATED", "This user is not deactivated.");
            }
            jdbc.sql("UPDATE app.app_user SET status = 'ACTIVE' WHERE id = :id").param("id", userId).update();
            audit.record(AuditAction.USER_REACTIVATED, "USER", userId, AuditResult.SUCCESS);
        }));
    }

    /** Clears another user's TOTP enrolment; an Organisation Admin then enrols again at the next sign-in. */
    void resetMfa(UUID userId) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        notSelf(admin, userId);
        guarded(() -> tx.executeWithoutResult(s -> {
            user(userId);
            int updated = jdbc.sql("UPDATE app.app_user SET mfa_secret_enc = NULL WHERE id = :id AND mfa_secret_enc IS NOT NULL")
                    .param("id", userId).update();
            if (updated == 0) {
                throw ApiException.conflict("MFA_NOT_ENROLLED", "This user has no authenticator enrolled.");
            }
            audit.record(AuditAction.USER_MFA_RESET, "USER", userId, AuditResult.SUCCESS);
        }));
    }

    /** Issues a password-reset link for an active user (shown to the admin and emailed when SMTP is configured). */
    LinkResult sendPasswordReset(UUID userId) {
        CurrentUser admin = ClientUsers.requireOrgAdmin();
        Message message = tx.execute(s -> {
            UserRow user = user(userId);
            if (!"ACTIVE".equals(user.status())) {
                throw ApiException.conflict("USER_NOT_ACTIVE", "Only an active user can reset a password.");
            }
            revokeOpen(userId);
            Message created = issue(admin, userId, user.email(), user.displayName(), Kind.PASSWORD_RESET);
            audit.record(AuditAction.PASSWORD_RESET_REQUESTED, "USER", userId, AuditResult.SUCCESS);
            return created;
        });
        return deliver(message);
    }

    // --- internals --------------------------------------------------------------------------------------------

    private record UserRow(UUID id, String email, String displayName, TenantRole role, String status) {
    }

    private UserRow user(UUID userId) {
        return jdbc.sql("SELECT id, email, display_name, role, status FROM app.app_user WHERE id = :id")
                .param("id", userId)
                .query((rs, n) -> new UserRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        TenantRole.valueOf(rs.getString(4)), rs.getString(5)))
                .optional().orElseThrow(ApiException::notFound);
    }

    private int revokeOpen(UUID userId) {
        return jdbc.sql("""
                UPDATE app.user_invitation SET revoked_at = clock_timestamp()
                WHERE user_id = :id AND used_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
                """).param("id", userId).update();
    }

    private Message issue(CurrentUser admin, UUID userId, String email, String name, Kind kind) {
        String token = AccountTokens.newToken();
        UUID invitationId = jdbc.sql("""
                INSERT INTO app.user_invitation (tenant_id, user_id, purpose, token_sha256, created_by_user, expires_at)
                VALUES (:tenant, :user, :purpose, :hash, :admin, now() + interval '72 hours')
                RETURNING id
                """).param("tenant", admin.tenantId()).param("user", userId).param("purpose", kind.name())
                .param("hash", AccountTokens.hash(token)).param("admin", admin.userId())
                .query(UUID.class).single();
        String slug = jdbc.sql("SELECT slug FROM app.tenant WHERE id = :id").param("id", admin.tenantId())
                .query(String.class).single();
        return new Message(admin.tenantId(), invitationId, kind, admin.tenantName(), slug, email, name, token);
    }

    private LinkResult deliver(Message message) {
        String status = accountMail.send(message);
        OffsetDateTime expires = tx.execute(s -> jdbc.sql("SELECT expires_at FROM app.user_invitation WHERE id = :id")
                .param("id", message.invitationId()).query(OffsetDateTime.class).single());
        UUID userId = tx.execute(s -> jdbc.sql("SELECT user_id FROM app.user_invitation WHERE id = :id")
                .param("id", message.invitationId()).query(UUID.class).single());
        return new LinkResult(userId, message.invitationId(), accountMail.link(message), status, expires);
    }

    private static void notSelf(CurrentUser admin, UUID userId) {
        if (admin.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_CHANGE_FORBIDDEN",
                    "Another Organisation Admin must make this change to your own account.");
        }
    }

    /** Translates the database's last-admin rule into a clear refusal. */
    private static void guarded(Runnable action) {
        try {
            action.run();
        } catch (DataAccessException e) {
            String text = Optional.ofNullable(e.getMostSpecificCause().getMessage()).orElse("");
            if (text.contains("last active Organisation Admin")) {
                throw ApiException.conflict("LAST_ORG_ADMIN",
                        "The organisation must keep at least one active Organisation Admin.");
            }
            throw e;
        }
    }
}
