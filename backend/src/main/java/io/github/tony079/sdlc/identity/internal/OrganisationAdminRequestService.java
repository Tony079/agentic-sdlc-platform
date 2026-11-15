package io.github.tony079.sdlc.identity.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.AccountMail.Kind;
import io.github.tony079.sdlc.identity.internal.AccountMail.Message;
import io.github.tony079.sdlc.identity.internal.OrganisationProfiles.Profile;
import io.github.tony079.sdlc.identity.internal.OrganisationProfiles.StoredProfile;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Garuda's actions on one organisation (GD-16, GD-17): its Organisation Admins (invite, disable, reactivate, reset the
 * authenticator) and its record. Each is a request by one Garuda Admin approved by another; Garuda never sees or
 * manages the organisation's other users.
 */
@Service
@ConditionalOnWebApplication
class OrganisationAdminRequestService {

    enum Kind2 { INVITE_ADMIN, DISABLE_ADMIN, REACTIVATE_ADMIN, RESET_ADMIN_MFA, UPDATE_PROFILE }

    record Admin(UUID id, String displayName, String email, String status, boolean mfaEnrolled,
                 OffsetDateTime invitationExpiresAt) {
    }

    record Request(UUID id, UUID tenantId, String organisationName, String kind, UUID subjectUserId, String subjectName,
                   String inviteEmail, String inviteDisplayName, Profile profile, String reason, String status,
                   UUID requestedBy, String requestedByName, OffsetDateTime requestedAt, UUID approvedBy,
                   String approvedByName, OffsetDateTime executedAt) {
    }

    record Record(UUID tenantId, String name, String slug, String status, Profile profile, OffsetDateTime updatedAt,
                  List<Admin> admins, List<Request> requests) {
    }

    record NewRequest(String kind, UUID subjectUserId, String email, String displayName, Profile profile, String reason) {
    }

    record Approved(Request request, String link, String emailStatus) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final StaffAccess staffAccess;
    private final PlatformAuditWriter platformAudit;
    private final AuditWriter audit;
    private final AccountMail accountMail;
    private final OrganisationProfiles profiles;

    OrganisationAdminRequestService(JdbcClient jdbc, PlatformTransactionManager transactionManager, StaffAccess staffAccess,
                                    PlatformAuditWriter platformAudit, AuditWriter audit, AccountMail accountMail,
                                    OrganisationProfiles profiles) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.staffAccess = staffAccess;
        this.platformAudit = platformAudit;
        this.audit = audit;
        this.accountMail = accountMail;
        this.profiles = profiles;
    }

    /** The organisation record, its Organisation Admins (never other users) and its Garuda requests. */
    Record record(UUID tenantId) {
        Object[] tenant = tx.execute(s -> {
            garudaAdmin();
            return tenant(tenantId);
        });
        Optional<StoredProfile> profile = TenantContext.callAs(tenantId, () -> tx.execute(s -> profiles.find(tenantId)));
        List<Admin> admins = TenantContext.callAs(tenantId, () -> tx.execute(s -> jdbc.sql("""
                SELECT u.id, u.display_name, u.email, u.status, u.mfa_enrolled_at IS NOT NULL,
                       (SELECT max(i.expires_at) FROM app.user_invitation i WHERE i.user_id = u.id AND i.purpose = 'INVITE'
                          AND i.used_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > clock_timestamp())
                FROM app.app_user u WHERE u.role = 'ORG_ADMIN' ORDER BY lower(u.display_name)
                """).query((rs, n) -> new Admin(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class))).list()));
        List<Request> requests = withNames(tx.execute(s -> jdbc.sql(SELECT
                + " WHERE r.subject_tenant_id = :t ORDER BY r.requested_at DESC LIMIT 100")
                .param("t", tenantId).query((rs, n) -> request(rs)).list()));
        return new Record(tenantId, (String) tenant[0], (String) tenant[1], (String) tenant[2],
                profile.map(StoredProfile::profile).orElse(null), profile.map(StoredProfile::updatedAt).orElse(null),
                admins, requests);
    }

    List<Request> list() {
        return withNames(tx.execute(s -> {
            garudaAdmin();
            return jdbc.sql(SELECT + " ORDER BY r.status <> 'REQUESTED', r.requested_at DESC LIMIT 200")
                    .query((rs, n) -> request(rs)).list();
        }));
    }

    Request create(UUID tenantId, NewRequest body) {
        Kind2 kind;
        try {
            kind = Kind2.valueOf(body.kind());
        } catch (RuntimeException e) {
            throw ApiException.validation("Unknown request type.");
        }
        String reason = body.reason() == null ? "" : body.reason().strip();
        if (reason.length() < 5 || reason.length() > 2000) {
            throw ApiException.validation("Give a reason of 5 to 2000 characters.");
        }
        String email = null;
        String name = null;
        String profileJson = null;
        if (kind == Kind2.INVITE_ADMIN) {
            email = body.email() == null ? "" : body.email().strip().toLowerCase(Locale.ROOT);
            name = body.displayName() == null ? "" : body.displayName().strip();
            if (!OrganisationUserService.EMAIL.matcher(email).matches() || email.length() > 320) {
                throw ApiException.validation("Enter a valid email address.");
            }
            if (name.isEmpty() || name.length() > 200) {
                throw ApiException.validation("Enter the person's name.");
            }
        } else if (kind == Kind2.UPDATE_PROFILE) {
            profileJson = profiles.toJson(profiles.validate(body.profile(), null));
        } else if (body.subjectUserId() == null) {
            throw ApiException.validation("Choose an Organisation Admin.");
        }
        String inviteEmail = email;
        String inviteName = name;
        String profile = profileJson;
        tx.execute(s -> {
            garudaAdmin();
            return tenant(tenantId);
        });
        // Checks inside the organisation (its own tenant scope): the subject is an Organisation Admin, the email fits.
        TenantContext.callAs(tenantId, () -> tx.execute(s -> {
            if (kind == Kind2.INVITE_ADMIN) {
                profiles.find(tenantId).ifPresent(p -> {
                    String domain = inviteEmail.substring(inviteEmail.indexOf('@') + 1);
                    if (!p.profile().allowedEmailDomains().contains(domain)) {
                        throw ApiException.validation("The email domain is not allowed for this organisation ("
                                + String.join(", ", p.profile().allowedEmailDomains()) + ").");
                    }
                });
                String existing = jdbc.sql("SELECT role || ':' || status FROM app.app_user WHERE lower(email) = :e")
                        .param("e", inviteEmail).query(String.class).optional().orElse(null);
                if (existing != null && !existing.equals("ORG_ADMIN:INVITED")) {
                    throw ApiException.conflict("USER_EXISTS", existing.startsWith("ORG_ADMIN")
                            ? "This person is already an Organisation Admin."
                            : "This person is already a user of the organisation; their Organisation Admin can change their role.");
                }
            } else if (kind != Kind2.UPDATE_PROFILE) {
                String status = jdbc.sql("SELECT status FROM app.app_user WHERE id = :id AND role = 'ORG_ADMIN'")
                        .param("id", body.subjectUserId()).query(String.class).optional()
                        .orElseThrow(() -> ApiException.validation("Garuda manages Organisation Admins only."));
                String problem = switch (kind) {
                    case DISABLE_ADMIN -> "ACTIVE".equals(status) ? null : "Only an active admin can be disabled.";
                    case REACTIVATE_ADMIN -> "DISABLED".equals(status) ? null : "Only a disabled admin can be reactivated.";
                    case RESET_ADMIN_MFA -> "ACTIVE".equals(status) ? null : "Only an active admin can be reset.";
                    default -> null;
                };
                if (problem != null) {
                    throw ApiException.conflict("ADMIN_STATE", problem);
                }
            }
            return null;
        }));
        UUID id = tx.execute(s -> jdbc.sql("""
                INSERT INTO app.organisation_admin_request (subject_tenant_id, kind, subject_user_id, invite_email,
                       invite_display_name, profile, reason, requested_by)
                VALUES (:t, :kind, :user, :email, :name, CAST(:profile AS jsonb), :reason, :me) RETURNING id
                """).param("t", tenantId).param("kind", kind.name())
                .param("user", kind == Kind2.INVITE_ADMIN || kind == Kind2.UPDATE_PROFILE ? null : body.subjectUserId())
                .param("email", inviteEmail).param("name", inviteName).param("profile", profile).param("reason", reason)
                .param("me", garudaAdmin()).query(UUID.class).single());
        platformAudit.record(current(), "ORGANISATION_" + kind.name() + "_REQUESTED", "ORG_ADMIN_REQUEST", id.toString(),
                tenantId, "SUCCESS");
        return find(id);
    }

    Approved approve(UUID requestId) {
        Request request = find(requestId);
        Kind2 kind = Kind2.valueOf(request.kind());
        Object[] tenant = tx.execute(s -> {
            UUID me = garudaAdmin();
            if (!"REQUESTED".equals(request.status())) {
                throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
            }
            if (me.equals(request.requestedBy())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                        "A different Garuda Admin must approve this request.");
            }
            return tenant(request.tenantId());
        });
        Message message;
        try {
            message = TenantContext.callAs(request.tenantId(), () -> tx.execute(s -> {
                UUID me = garudaAdmin();
                if (jdbc.sql("""
                        UPDATE app.organisation_admin_request SET status = 'EXECUTED', approved_by = :me,
                               executed_at = clock_timestamp()
                        WHERE id = :id AND status = 'REQUESTED'
                        """).param("me", me).param("id", requestId).update() != 1) {
                    throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
                }
                return execute(kind, request, me, (String) tenant[0], (String) tenant[1]);
            }));
        } catch (DataAccessException e) {
            String text = Optional.ofNullable(e.getMostSpecificCause().getMessage()).orElse("");
            platformAudit.record(current(), "ORGANISATION_" + kind.name() + "_APPROVED", "ORG_ADMIN_REQUEST",
                    requestId.toString(), request.tenantId(), "DENIED");
            if (text.contains("last active Organisation Admin")) {
                throw ApiException.conflict("LAST_ORG_ADMIN",
                        "This is the organisation's only active admin. Invite another admin first.");
            }
            throw ApiException.conflict("ORG_ADMIN_CHANGE_REFUSED", "The change was refused: " + text.split("\n")[0]);
        }
        platformAudit.record(current(), "ORGANISATION_" + kind.name() + "_EXECUTED", "TENANT",
                request.tenantId().toString(), request.tenantId(), "SUCCESS");
        if (message == null) {
            return new Approved(find(requestId), null, null);
        }
        String status = accountMail.send(message);
        return new Approved(find(requestId), accountMail.link(message), status);
    }

    Request decide(UUID requestId, boolean cancel) {
        UUID me = tx.execute(s -> {
            UUID actor = garudaAdmin();
            if (jdbc.sql("""
                    UPDATE app.organisation_admin_request SET status = :status, decided_by = :me, decided_at = clock_timestamp()
                    WHERE id = :id AND status = 'REQUESTED' AND (:cancel = false OR requested_by = :me)
                    """).param("status", cancel ? "CANCELLED" : "REJECTED").param("me", actor).param("id", requestId)
                    .param("cancel", cancel).update() != 1) {
                throw ApiException.conflict("REQUEST_STATE",
                        cancel ? "Only the requester can cancel an open request." : "This request is already decided.");
            }
            return actor;
        });
        platformAudit.record(me, cancel ? "ORG_ADMIN_REQUEST_CANCELLED" : "ORG_ADMIN_REQUEST_REJECTED", "ORG_ADMIN_REQUEST",
                requestId.toString(), null, "SUCCESS");
        return find(requestId);
    }

    // --- internals ------------------------------------------------------------------------------------------------

    private Message execute(Kind2 kind, Request request, UUID me, String orgName, String slug) {
        UUID tenantId = request.tenantId();
        switch (kind) {
            case INVITE_ADMIN -> {
                UUID userId = jdbc.sql("SELECT id FROM app.app_user WHERE lower(email) = :e AND role = 'ORG_ADMIN' AND status = 'INVITED'")
                        .param("e", request.inviteEmail()).query(UUID.class).optional().orElse(null);
                if (userId == null) {
                    userId = jdbc.sql("""
                            INSERT INTO app.app_user (tenant_id, email, display_name, role, status)
                            VALUES (:t, :email, :name, 'ORG_ADMIN', 'INVITED') RETURNING id
                            """).param("t", tenantId).param("email", request.inviteEmail())
                            .param("name", request.inviteDisplayName()).query(UUID.class).single();
                } else {
                    jdbc.sql("""
                            UPDATE app.user_invitation SET revoked_at = clock_timestamp()
                            WHERE user_id = :u AND used_at IS NULL AND revoked_at IS NULL
                            """).param("u", userId).update();
                }
                String token = AccountTokens.newToken();
                UUID invitation = jdbc.sql("""
                        INSERT INTO app.user_invitation (tenant_id, user_id, purpose, token_sha256, created_by_staff, expires_at)
                        VALUES (:t, :u, 'INVITE', :hash, :me, now() + interval '72 hours') RETURNING id
                        """).param("t", tenantId).param("u", userId).param("hash", AccountTokens.hash(token))
                        .param("me", me).query(UUID.class).single();
                audit.record(AuditAction.USER_INVITED, "USER", userId, AuditResult.SUCCESS);
                return new Message(tenantId, invitation, Kind.INVITE, orgName, slug, request.inviteEmail(),
                        request.inviteDisplayName(), token);
            }
            case DISABLE_ADMIN -> {
                jdbc.sql("UPDATE app.app_user SET status = 'DISABLED' WHERE id = :id").param("id", request.subjectUserId()).update();
                audit.record(AuditAction.USER_DEACTIVATED, "USER", request.subjectUserId(), AuditResult.SUCCESS);
            }
            case REACTIVATE_ADMIN -> {
                jdbc.sql("UPDATE app.app_user SET status = 'ACTIVE' WHERE id = :id").param("id", request.subjectUserId()).update();
                audit.record(AuditAction.USER_REACTIVATED, "USER", request.subjectUserId(), AuditResult.SUCCESS);
            }
            case RESET_ADMIN_MFA -> {
                jdbc.sql("UPDATE app.app_user SET mfa_secret_enc = NULL WHERE id = :id").param("id", request.subjectUserId()).update();
                audit.record(AuditAction.USER_MFA_RESET, "USER", request.subjectUserId(), AuditResult.SUCCESS);
            }
            case UPDATE_PROFILE -> profiles.save(tenantId, request.profile());
            default -> throw new IllegalStateException(kind.name());
        }
        return null;
    }

    private Object[] tenant(UUID tenantId) {
        return jdbc.sql("SELECT name, slug, status FROM app.tenant WHERE id = :id AND status IN ('ACTIVE', 'SUSPENDED')")
                .param("id", tenantId).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3)})
                .optional().orElseThrow(ApiException::notFound);
    }

    private UUID garudaAdmin() {
        return staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
    }

    private static UUID current() {
        return TenantContext.currentActor().orElse(null);
    }

    private static final String SELECT = """
            SELECT r.id, r.subject_tenant_id, t.name, r.kind, r.subject_user_id, r.invite_email, r.invite_display_name,
                   r.profile::text, r.reason, r.status, r.requested_by, rq.display_name, r.requested_at, r.approved_by,
                   ap.display_name, r.executed_at
            FROM app.organisation_admin_request r
            JOIN app.tenant t ON t.id = r.subject_tenant_id
            JOIN app.staff_user rq ON rq.id = r.requested_by
            LEFT JOIN app.staff_user ap ON ap.id = r.approved_by
            """;

    private Request find(UUID id) {
        Request found = tx.execute(s -> jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id)
                .query((rs, n) -> request(rs)).optional().orElseThrow(ApiException::notFound));
        return withNames(List.of(found)).get(0);
    }

    /** Adds the subject admin's name, read inside that organisation's own scope (Organisation Admins only). */
    private List<Request> withNames(List<Request> requests) {
        return requests.stream().<Request>map(r -> r.subjectUserId() == null ? r : new Request(r.id(), r.tenantId(),
                r.organisationName(), r.kind(), r.subjectUserId(),
                TenantContext.callAs(r.tenantId(), () -> tx.execute(s -> jdbc.sql(
                        "SELECT display_name FROM app.app_user WHERE id = :id AND role = 'ORG_ADMIN'")
                        .param("id", r.subjectUserId()).query(String.class).optional().orElse(null))),
                r.inviteEmail(), r.inviteDisplayName(), r.profile(), r.reason(), r.status(), r.requestedBy(),
                r.requestedByName(), r.requestedAt(), r.approvedBy(), r.approvedByName(), r.executedAt())).toList();
    }

    private Request request(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID tenantId = rs.getObject(2, UUID.class);
        UUID subject = rs.getObject(5, UUID.class);
        return new Request(rs.getObject(1, UUID.class), tenantId, rs.getString(3), rs.getString(4), subject, null,
                rs.getString(6), rs.getString(7), profiles.fromJson(rs.getString(8)), rs.getString(9), rs.getString(10),
                rs.getObject(11, UUID.class), rs.getString(12), rs.getObject(13, OffsetDateTime.class),
                rs.getObject(14, UUID.class), rs.getString(15), rs.getObject(16, OffsetDateTime.class));
    }
}
