package io.github.tony079.sdlc.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.AccountMail.Kind;
import io.github.tony079.sdlc.identity.internal.AccountMail.Message;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staff administration in the Garuda console (GD-15). Only the first two administrators are created on the command
 * line (O-13 bootstrap). Every other staff change is a request by one staff administrator, approved by a different one
 * (neither may be the person changed), and executed in the approving transaction. Invited staff set their own password
 * through a single-use link and then enrol an authenticator at first sign-in. The database enforces all of it
 * (0031-staff-administration).
 */
@Service
@ConditionalOnWebApplication
class StaffAdministrationService {

    enum Kind2 { INVITE, GRANT_CAPABILITY, REVOKE_CAPABILITY, ASSIGN_TENANT, REVOKE_ASSIGNMENT, DISABLE, REACTIVATE, RESET_MFA,
        RESET_PASSWORD }

    record StaffMember(UUID id, String email, String displayName, String status, boolean mfaEnrolled,
                       OffsetDateTime createdAt, List<String> capabilities, List<Assignment> assignments,
                       PendingLink pendingLink) {
    }

    record Assignment(UUID tenantId, String tenantName) {
    }

    record PendingLink(String purpose, OffsetDateTime expiresAt, String emailStatus) {
    }

    record Request(UUID id, String kind, UUID subjectStaffId, String subjectName, String inviteEmail,
                   String inviteDisplayName, List<String> inviteCapabilities, String capability, UUID tenantId,
                   String tenantName, String reason, String status, UUID requestedBy, String requestedByName,
                   OffsetDateTime requestedAt, UUID approvedBy, String approvedByName, OffsetDateTime executedAt) {
    }

    record NewRequest(String kind, UUID subjectStaffId, String email, String displayName, List<String> capabilities,
                      String capability, UUID tenantId, String reason) {
    }

    record Approved(Request request, String link, String emailStatus) {
    }

    record Tenant(UUID id, String name) {
    }

    record LinkDetails(String email, String displayName, String purpose, OffsetDateTime expiresAt) {
    }

    private static final String OPERATOR = "garuda-console";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final StaffAccess staffAccess;
    private final PlatformAuditWriter platformAudit;
    private final AccountMail accountMail;
    private final PasswordEncoder passwords;

    StaffAdministrationService(JdbcClient jdbc, PlatformTransactionManager transactionManager, StaffAccess staffAccess,
                               PlatformAuditWriter platformAudit, AccountMail accountMail, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.staffAccess = staffAccess;
        this.platformAudit = platformAudit;
        this.accountMail = accountMail;
        this.passwords = passwords;
    }

    // --- reads ----------------------------------------------------------------------------------------------------

    List<StaffMember> staff() {
        return tx.execute(s -> {
            admin();
            return jdbc.sql("""
                    SELECT u.id, u.email, u.display_name, u.status, u.mfa_enrolled_at IS NOT NULL, u.created_at,
                           coalesce((SELECT array_agg(g.capability ORDER BY g.capability) FROM app.staff_capability_grant g
                                     WHERE g.staff_id = u.id AND g.revoked_at IS NULL), '{}'),
                           coalesce((SELECT array_agg(t.id::text || '|' || t.name ORDER BY t.name)
                                     FROM app.staff_tenant_assignment a JOIN app.tenant t ON t.id = a.assigned_tenant_id
                                     WHERE a.staff_id = u.id AND a.revoked_at IS NULL), '{}'),
                           i.purpose, i.expires_at, i.email_status
                    FROM app.staff_user u
                    LEFT JOIN LATERAL (SELECT * FROM app.staff_invitation x WHERE x.staff_id = u.id AND x.used_at IS NULL
                                       AND x.revoked_at IS NULL AND x.expires_at > clock_timestamp()
                                       ORDER BY x.created_at DESC LIMIT 1) i ON true
                    ORDER BY u.status = 'DISABLED', lower(u.display_name)
                    """).query((rs, n) -> new StaffMember(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getBoolean(5), rs.getObject(6, OffsetDateTime.class), strings(rs, 7),
                    strings(rs, 8).stream().map(v -> {
                        String[] parts = v.split("\\|", 2);
                        return new Assignment(UUID.fromString(parts[0]), parts[1]);
                    }).toList(),
                    rs.getString(9) == null ? null
                            : new PendingLink(rs.getString(9), rs.getObject(10, OffsetDateTime.class), rs.getString(11))))
                    .list();
        });
    }

    List<Request> requests() {
        return tx.execute(s -> {
            admin();
            return jdbc.sql(REQUEST_SELECT + " ORDER BY r.status <> 'REQUESTED', r.requested_at DESC LIMIT 200")
                    .query((rs, n) -> request(rs)).list();
        });
    }

    List<Tenant> tenants() {
        return tx.execute(s -> {
            admin();
            return jdbc.sql("SELECT id, name FROM app.tenant WHERE status = 'ACTIVE' ORDER BY lower(name)")
                    .query((rs, n) -> new Tenant(rs.getObject(1, UUID.class), rs.getString(2))).list();
        });
    }

    // --- requests -------------------------------------------------------------------------------------------------

    Request create(NewRequest body) {
        Kind2 kind = parseKind(body.kind());
        String reason = body.reason() == null ? "" : body.reason().strip();
        if (reason.length() < 5 || reason.length() > 2000) {
            throw ApiException.validation("Give a reason of 5 to 2000 characters.");
        }
        String email = null;
        String name = null;
        List<String> capabilities = List.of();
        String capability = null;
        if (kind == Kind2.INVITE) {
            email = body.email() == null ? "" : body.email().strip().toLowerCase(Locale.ROOT);
            name = body.displayName() == null ? "" : body.displayName().strip();
            if (!OrganisationUserService.EMAIL.matcher(email).matches() || email.length() > 320) {
                throw ApiException.validation("Enter a valid email address.");
            }
            if (name.isEmpty() || name.length() > 200) {
                throw ApiException.validation("Enter the person's name.");
            }
            capabilities = capabilities(body.capabilities());
        } else if (body.subjectStaffId() == null) {
            throw ApiException.validation("Choose a staff member.");
        }
        if (kind == Kind2.GRANT_CAPABILITY || kind == Kind2.REVOKE_CAPABILITY) {
            capability = capabilities(body.capability() == null ? null : List.of(body.capability())).stream().findFirst()
                    .orElseThrow(() -> ApiException.validation("Choose a capability."));
        }
        if ((kind == Kind2.ASSIGN_TENANT || kind == Kind2.REVOKE_ASSIGNMENT) && body.tenantId() == null) {
            throw ApiException.validation("Choose an organisation.");
        }
        String inviteEmail = email;
        String inviteName = name;
        List<String> inviteCapabilities = capabilities;
        String grant = capability;
        UUID id;
        try {
            id = tx.execute(s -> {
                UUID me = admin();
                if (kind != Kind2.INVITE) {
                    if (me.equals(body.subjectStaffId())) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "SELF_CHANGE_FORBIDDEN",
                                "Two other administrators must make changes to your own account.");
                    }
                    precheck(kind, body.subjectStaffId(), grant, body.tenantId());
                } else if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.staff_user WHERE lower(email) = :email)")
                        .param("email", inviteEmail).query(Boolean.class).single()) {
                    throw ApiException.conflict("STAFF_EXISTS", "A staff member with this email already exists.");
                }
                return jdbc.sql("""
                        INSERT INTO app.staff_admin_request (kind, subject_staff_id, invite_email, invite_display_name,
                               invite_capabilities, capability, target_tenant_id, reason, requested_by)
                        VALUES (:kind, :subject, :email, :name, CAST(:caps AS text[]), :capability, :tenant, :reason, :me)
                        RETURNING id
                        """).param("kind", kind.name()).param("subject", kind == Kind2.INVITE ? null : body.subjectStaffId())
                        .param("email", inviteEmail).param("name", inviteName)
                        .param("caps", "{" + String.join(",", inviteCapabilities) + "}").param("capability", grant)
                        .param("tenant", body.tenantId()).param("reason", reason).param("me", me)
                        .query(UUID.class).single();
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("REQUEST_OPEN", "An invitation for this email is already waiting for approval.");
        }
        platformAudit.record(current(), "STAFF_" + kind.name() + "_REQUESTED", "STAFF_REQUEST", id.toString(), null,
                "SUCCESS");
        return find(id);
    }

    /** The second administrator approves; the change is executed in the same transaction. */
    Approved approve(UUID requestId) {
        Request request = find(requestId);
        Kind2 kind = Kind2.valueOf(request.kind());
        String[] token = new String[1];
        Message message;
        try {
            message = tx.execute(s -> {
                UUID me = admin();
                if (!"REQUESTED".equals(request.status())) {
                    throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
                }
                if (me.equals(request.requestedBy())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                            "A different staff administrator must approve this request.");
                }
                if (me.equals(request.subjectStaffId())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "SELF_CHANGE_FORBIDDEN",
                            "You cannot approve a change to your own account.");
                }
                UUID subject = kind == Kind2.INVITE ? UUID.randomUUID() : request.subjectStaffId();
                if (kind != Kind2.INVITE) {
                    precheck(kind, subject, request.capability(), request.tenantId());
                }
                int updated = jdbc.sql("""
                        UPDATE app.staff_admin_request SET status = 'EXECUTED', approved_by = :me,
                               executed_at = clock_timestamp(), subject_staff_id = :subject
                        WHERE id = :id AND status = 'REQUESTED'
                        """).param("me", me).param("subject", subject).param("id", requestId).update();
                if (updated != 1) {
                    throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
                }
                return execute(kind, request, subject, me, token);
            });
        } catch (DataAccessException e) {
            platformAudit.record(current(), "STAFF_" + kind.name() + "_APPROVED", "STAFF_REQUEST", requestId.toString(),
                    null, "DENIED");
            throw ApiException.conflict("STAFF_CHANGE_REFUSED", "The change was refused: "
                    + Optional.ofNullable(e.getMostSpecificCause().getMessage()).map(m -> m.split("\n")[0]).orElse(""));
        }
        platformAudit.record(current(), "STAFF_" + kind.name() + "_EXECUTED", "STAFF", find(requestId).subjectStaffId()
                .toString(), request.tenantId(), "SUCCESS");
        if (message == null) {
            return new Approved(find(requestId), null, null);
        }
        String status = accountMail.send(message);
        return new Approved(find(requestId), accountMail.link(message), status);
    }

    Request decide(UUID requestId, boolean cancel) {
        UUID me = tx.execute(s -> {
            UUID actor = admin();
            int updated = jdbc.sql("""
                    UPDATE app.staff_admin_request SET status = :status, decided_by = :me, decided_at = clock_timestamp()
                    WHERE id = :id AND status = 'REQUESTED' AND (:cancel = false OR requested_by = :me)
                    """).param("status", cancel ? "CANCELLED" : "REJECTED").param("me", actor).param("id", requestId)
                    .param("cancel", cancel).update();
            if (updated != 1) {
                throw ApiException.conflict("REQUEST_STATE",
                        cancel ? "Only the requester can cancel an open request." : "This request is already decided.");
            }
            return actor;
        });
        platformAudit.record(me, cancel ? "STAFF_REQUEST_CANCELLED" : "STAFF_REQUEST_REJECTED", "STAFF_REQUEST",
                requestId.toString(), null, "SUCCESS");
        return find(requestId);
    }

    /** A new invitation link for someone who has not accepted yet (one administrator; the earlier link is revoked). */
    Approved resendInvitation(UUID staffId) {
        Message message = tx.execute(s -> {
            UUID me = admin();
            Object[] user = jdbc.sql("SELECT email, display_name, status FROM app.staff_user WHERE id = :id")
                    .param("id", staffId).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3)})
                    .optional().orElseThrow(ApiException::notFound);
            if (!"INVITED".equals(user[2])) {
                throw ApiException.conflict("STAFF_NOT_INVITED", "This person has already accepted the invitation.");
            }
            jdbc.sql("""
                    UPDATE app.staff_invitation SET revoked_at = clock_timestamp()
                    WHERE staff_id = :id AND used_at IS NULL AND revoked_at IS NULL
                    """).param("id", staffId).update();
            String token = AccountTokens.newToken();
            UUID invitation = insertLink(staffId, "INVITE", token, me);
            return new Message(null, invitation, Kind.INVITE, "Garuda", null, (String) user[0], (String) user[1], token);
        });
        platformAudit.record(current(), "STAFF_INVITATION_RESENT", "STAFF", staffId.toString(), null, "SUCCESS");
        String status = accountMail.send(message);
        return new Approved(null, accountMail.link(message), status);
    }

    // --- public links ---------------------------------------------------------------------------------------------

    LinkDetails lookup(String token, String purpose) {
        if (!AccountTokens.wellFormed(token)) {
            throw unusable();
        }
        return tx.execute(s -> link(token, purpose, false).map(l -> new LinkDetails((String) l[2], (String) l[3], purpose,
                (OffsetDateTime) l[4])).orElseThrow(StaffAdministrationService::unusable));
    }

    /** Sets the password (and, for an invitation, activates the account and opens the 72-hour MFA enrolment window). */
    void complete(String token, String password, String purpose) {
        if (!AccountTokens.acceptablePassword(password)) {
            throw ApiException.validation("Choose a password of 12 to 200 characters.");
        }
        if (!AccountTokens.wellFormed(token)) {
            throw unusable();
        }
        String hash = passwords.encode(password);
        UUID staffId = tx.execute(s -> {
            Object[] l = link(token, purpose, true).orElseThrow(StaffAdministrationService::unusable);
            UUID linkId = (UUID) l[0];
            UUID staff = (UUID) l[1];
            if (jdbc.sql("""
                    UPDATE app.staff_invitation SET used_at = clock_timestamp()
                    WHERE id = :id AND used_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
                    """).param("id", linkId).update() != 1) {
                throw unusable();
            }
            if ("INVITE".equals(purpose)) {
                jdbc.sql("""
                        UPDATE app.staff_user SET password_hash = :hash, status = 'ACTIVE',
                               mfa_enrol_before = clock_timestamp() + interval '72 hours'
                        WHERE id = :id AND status = 'INVITED'
                        """).param("hash", hash).param("id", staff).update();
            } else {
                jdbc.sql("UPDATE app.staff_user SET password_hash = :hash WHERE id = :id AND status = 'ACTIVE'")
                        .param("hash", hash).param("id", staff).update();
            }
            jdbc.sql("""
                    UPDATE app.staff_invitation SET revoked_at = clock_timestamp()
                    WHERE staff_id = :staff AND id <> :id AND used_at IS NULL AND revoked_at IS NULL
                    """).param("staff", staff).param("id", linkId).update();
            return staff;
        });
        platformAudit.record(staffId, "INVITE".equals(purpose) ? "STAFF_INVITATION_ACCEPTED" : "STAFF_PASSWORD_RESET",
                "STAFF", staffId.toString(), null, "SUCCESS");
    }

    // --- internals ------------------------------------------------------------------------------------------------

    private Message execute(Kind2 kind, Request request, UUID subject, UUID me, String[] token) {
        switch (kind) {
            case INVITE -> {
                jdbc.sql("INSERT INTO app.staff_user (id, email, display_name, status) VALUES (:id, :email, :name, 'INVITED')")
                        .param("id", subject).param("email", request.inviteEmail())
                        .param("name", request.inviteDisplayName()).update();
                for (String capability : request.inviteCapabilities()) {
                    grant(subject, capability, request.requestedBy(), me);
                }
                token[0] = AccountTokens.newToken();
                UUID invitation = insertLink(subject, "INVITE", token[0], me);
                return new Message(null, invitation, Kind.INVITE, "Garuda", null, request.inviteEmail(),
                        request.inviteDisplayName(), token[0]);
            }
            case GRANT_CAPABILITY -> grant(subject, request.capability(), request.requestedBy(), me);
            case REVOKE_CAPABILITY -> jdbc.sql("""
                    UPDATE app.staff_capability_grant SET revoked_at = clock_timestamp(), revoked_by = :me
                    WHERE staff_id = :staff AND capability = :capability AND revoked_at IS NULL
                    """).param("me", me).param("staff", subject).param("capability", request.capability()).update();
            case ASSIGN_TENANT -> jdbc.sql("""
                    INSERT INTO app.staff_tenant_assignment (staff_id, assigned_tenant_id, requested_by, approved_by, operator)
                    VALUES (:staff, :tenant, :requester, :me, :operator)
                    """).param("staff", subject).param("tenant", request.tenantId()).param("requester", request.requestedBy())
                    .param("me", me).param("operator", OPERATOR).update();
            case REVOKE_ASSIGNMENT -> jdbc.sql("""
                    UPDATE app.staff_tenant_assignment SET revoked_at = clock_timestamp(), revoked_by = :me
                    WHERE staff_id = :staff AND assigned_tenant_id = :tenant AND revoked_at IS NULL
                    """).param("me", me).param("staff", subject).param("tenant", request.tenantId()).update();
            case DISABLE -> {
                jdbc.sql("UPDATE app.staff_user SET status = 'DISABLED' WHERE id = :id").param("id", subject).update();
                jdbc.sql("""
                        UPDATE app.staff_invitation SET revoked_at = clock_timestamp()
                        WHERE staff_id = :id AND used_at IS NULL AND revoked_at IS NULL
                        """).param("id", subject).update();
            }
            case REACTIVATE -> jdbc.sql("UPDATE app.staff_user SET status = 'ACTIVE' WHERE id = :id")
                    .param("id", subject).update();
            case RESET_MFA -> jdbc.sql("""
                    UPDATE app.staff_user SET mfa_secret_enc = NULL, mfa_enrolled_at = NULL, totp_last_step = NULL,
                           mfa_enrol_before = clock_timestamp() + interval '72 hours'
                    WHERE id = :id
                    """).param("id", subject).update();
            case RESET_PASSWORD -> {
                Object[] user = jdbc.sql("SELECT email, display_name FROM app.staff_user WHERE id = :id").param("id", subject)
                        .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2)}).single();
                token[0] = AccountTokens.newToken();
                UUID link = insertLink(subject, "PASSWORD_RESET", token[0], me);
                return new Message(null, link, Kind.PASSWORD_RESET, "Garuda", null, (String) user[0], (String) user[1],
                        token[0]);
            }
            default -> throw new IllegalStateException(kind.name());
        }
        return null;
    }

    private void grant(UUID staff, String capability, UUID requester, UUID approver) {
        jdbc.sql("""
                INSERT INTO app.staff_capability_grant (staff_id, capability, requested_by, approved_by, operator)
                VALUES (:staff, :capability, :requester, :approver, :operator)
                """).param("staff", staff).param("capability", capability).param("requester", requester)
                .param("approver", approver).param("operator", OPERATOR).update();
    }

    private UUID insertLink(UUID staff, String purpose, String token, UUID me) {
        return jdbc.sql("""
                INSERT INTO app.staff_invitation (staff_id, purpose, token_sha256, created_by_staff, expires_at)
                VALUES (:staff, :purpose, :hash, :me, now() + interval '72 hours') RETURNING id
                """).param("staff", staff).param("purpose", purpose).param("hash", AccountTokens.hash(token))
                .param("me", me).query(UUID.class).single();
    }

    /** Refuses requests that cannot be executed in the current state, with a clear reason. */
    private void precheck(Kind2 kind, UUID subject, String capability, UUID tenant) {
        String status = jdbc.sql("SELECT status FROM app.staff_user WHERE id = :id").param("id", subject)
                .query(String.class).optional().orElseThrow(ApiException::notFound);
        boolean holds = capability != null && jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app.staff_capability_grant WHERE staff_id = :id AND capability = :c
                               AND revoked_at IS NULL)
                """).param("id", subject).param("c", capability).query(Boolean.class).single();
        boolean assigned = tenant != null && jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app.staff_tenant_assignment WHERE staff_id = :id
                               AND assigned_tenant_id = :t AND revoked_at IS NULL)
                """).param("id", subject).param("t", tenant).query(Boolean.class).single();
        String problem = switch (kind) {
            case GRANT_CAPABILITY -> holds ? "This person already holds the capability."
                    : "DISABLED".equals(status) ? "Reactivate this person first." : null;
            case REVOKE_CAPABILITY -> holds ? null : "This person does not hold the capability.";
            case ASSIGN_TENANT -> assigned ? "This person is already assigned to the organisation."
                    : "DISABLED".equals(status) ? "Reactivate this person first." : null;
            case REVOKE_ASSIGNMENT -> assigned ? null : "This person is not assigned to the organisation.";
            case DISABLE -> "ACTIVE".equals(status) ? null : "Only an active staff member can be disabled.";
            case REACTIVATE -> "DISABLED".equals(status) ? null : "Only a disabled staff member can be reactivated.";
            case RESET_MFA, RESET_PASSWORD -> "ACTIVE".equals(status) ? null : "Only an active staff member can be reset.";
            default -> null;
        };
        if (problem != null) {
            throw ApiException.conflict("STAFF_STATE", problem);
        }
    }

    private Optional<Object[]> link(String token, String purpose, boolean lock) {
        return jdbc.sql("""
                SELECT i.id, i.staff_id, u.email, u.display_name, i.expires_at
                FROM app.staff_invitation i JOIN app.staff_user u ON u.id = i.staff_id
                WHERE i.token_sha256 = :hash AND i.purpose = :purpose AND i.used_at IS NULL AND i.revoked_at IS NULL
                  AND i.expires_at > clock_timestamp()
                  AND u.status = CASE WHEN i.purpose = 'INVITE' THEN 'INVITED' ELSE 'ACTIVE' END
                """ + (lock ? " FOR UPDATE OF i" : ""))
                .param("hash", AccountTokens.hash(token)).param("purpose", purpose)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class)})
                .optional();
    }

    private UUID admin() {
        return staffAccess.requirePlatformCapability(StaffCapability.STAFF_ADMIN);
    }

    private static UUID current() {
        return io.github.tony079.sdlc.tenancy.api.TenantContext.currentActor().orElse(null);
    }

    private static Kind2 parseKind(String kind) {
        try {
            return Kind2.valueOf(kind);
        } catch (RuntimeException e) {
            throw ApiException.validation("Unknown request type.");
        }
    }

    private static List<String> capabilities(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        for (String value : values) {
            try {
                known.add(StaffCapability.valueOf(value).name());
            } catch (RuntimeException e) {
                throw ApiException.validation("Unknown capability: " + value);
            }
        }
        return List.copyOf(known);
    }

    private static List<String> strings(ResultSet rs, int column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static final String REQUEST_SELECT = """
            SELECT r.id, r.kind, r.subject_staff_id, su.display_name, r.invite_email, r.invite_display_name,
                   r.invite_capabilities, r.capability, r.target_tenant_id, t.name, r.reason, r.status, r.requested_by,
                   rq.display_name, r.requested_at, r.approved_by, ap.display_name, r.executed_at
            FROM app.staff_admin_request r
            JOIN app.staff_user rq ON rq.id = r.requested_by
            LEFT JOIN app.staff_user su ON su.id = r.subject_staff_id
            LEFT JOIN app.staff_user ap ON ap.id = r.approved_by
            LEFT JOIN app.tenant t ON t.id = r.target_tenant_id
            """;

    private Request find(UUID id) {
        return tx.execute(s -> jdbc.sql(REQUEST_SELECT + " WHERE r.id = :id").param("id", id)
                .query((rs, n) -> request(rs)).optional().orElseThrow(ApiException::notFound));
    }

    private static Request request(ResultSet rs) throws SQLException {
        return new Request(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5), rs.getString(6), strings(rs, 7), rs.getString(8), rs.getObject(9, UUID.class),
                rs.getString(10), rs.getString(11), rs.getString(12), rs.getObject(13, UUID.class), rs.getString(14),
                rs.getObject(15, OffsetDateTime.class), rs.getObject(16, UUID.class), rs.getString(17),
                rs.getObject(18, OffsetDateTime.class));
    }

    private static ApiException unusable() {
        return new ApiException(HttpStatus.GONE, "LINK_UNUSABLE",
                "This link is no longer valid. Ask a Garuda administrator for a new one.");
    }
}
