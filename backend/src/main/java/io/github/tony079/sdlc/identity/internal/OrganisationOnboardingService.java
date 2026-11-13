package io.github.tony079.sdlc.identity.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.AccountMail.Kind;
import io.github.tony079.sdlc.identity.internal.AccountMail.Message;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Garuda onboarding of client organisations (FR-ORG-01..03, GD-03): one ORG_ONBOARD holder requests, a different one
 * approves. Approval creates the organisation and invites its first Organisation Admin in one transaction; suspension
 * and reactivation follow the same two-person rule. The database enforces both people and the same-transaction link
 * (organisation_onboarding_guard, tenant_insert_guard, app_user_guard, user_invitation_guard).
 */
@Service
@ConditionalOnWebApplication
class OrganisationOnboardingService {

    static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    record OnboardingRequest(UUID id, String organisationName, String organisationSlug, String adminEmail,
                             String adminDisplayName, String status, UUID requestedBy, String requestedByName,
                             OffsetDateTime requestedAt, UUID approvedBy, String approvedByName,
                             OffsetDateTime executedAt, UUID createdTenantId, String decisionNote) {
    }

    record StatusRequest(UUID id, UUID tenantId, String organisationName, String kind, String status, String reason,
                         UUID requestedBy, String requestedByName, OffsetDateTime requestedAt, UUID approvedBy,
                         OffsetDateTime executedAt) {
    }

    record Approved(OnboardingRequest request, String invitationLink, String emailStatus) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final StaffAccess staffAccess;
    private final PlatformAuditWriter platformAudit;
    private final AccountMail accountMail;

    OrganisationOnboardingService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
                                  StaffAccess staffAccess, PlatformAuditWriter platformAudit, AccountMail accountMail) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.staffAccess = staffAccess;
        this.platformAudit = platformAudit;
        this.accountMail = accountMail;
    }

    List<OnboardingRequest> listOnboarding() {
        return tx.execute(s -> {
            staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
            return jdbc.sql(ONBOARDING_SELECT + " ORDER BY r.status <> 'REQUESTED', r.requested_at DESC LIMIT 200")
                    .query((rs, n) -> onboarding(rs)).list();
        });
    }

    OnboardingRequest requestOnboarding(String name, String slug, String adminEmail, String adminName) {
        String orgName = name == null ? "" : name.strip();
        String orgSlug = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
        String email = adminEmail == null ? "" : adminEmail.strip().toLowerCase(Locale.ROOT);
        String admin = adminName == null ? "" : adminName.strip();
        if (orgName.isEmpty() || orgName.length() > 200) {
            throw ApiException.validation("Enter the organisation name (up to 200 characters).");
        }
        if (!SLUG.matcher(orgSlug).matches()) {
            throw ApiException.validation(
                    "The sign-in code needs 2 to 63 lower-case letters, digits or hyphens, starting with a letter or digit.");
        }
        if (!OrganisationUserService.EMAIL.matcher(email).matches() || email.length() > 320) {
            throw ApiException.validation("Enter a valid email address for the first Organisation Admin.");
        }
        if (admin.isEmpty() || admin.length() > 200) {
            throw ApiException.validation("Enter the first Organisation Admin's name.");
        }
        UUID id;
        try {
            id = tx.execute(s -> {
                UUID staff = staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
                if (slugTaken(orgSlug)) {
                    throw ApiException.conflict("SLUG_TAKEN", "Another organisation already uses this sign-in code.");
                }
                if (nameTaken(orgName)) {
                    throw ApiException.conflict("NAME_TAKEN", "Another organisation already has this name.");
                }
                return jdbc.sql("""
                        INSERT INTO app.organisation_onboarding_request (organisation_name, organisation_slug, admin_email,
                                                                         admin_display_name, requested_by)
                        VALUES (:name, :slug, :email, :admin, :staff) RETURNING id
                        """).param("name", orgName).param("slug", orgSlug).param("email", email).param("admin", admin)
                        .param("staff", staff).query(UUID.class).single();
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "An open request already uses this sign-in code.");
        }
        platformAudit.record(current(), "ORGANISATION_ONBOARDING_REQUESTED", "ONBOARDING_REQUEST", id.toString(), null,
                "SUCCESS");
        return find(id);
    }

    /** The second person approves: the organisation and its first Organisation Admin are created and invited. */
    Approved approveOnboarding(UUID requestId) {
        UUID staff = tx.execute(s -> staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD));
        OnboardingRequest request = find(requestId);
        if (!"REQUESTED".equals(request.status())) {
            throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
        }
        if (staff.equals(request.requestedBy())) {
            platformAudit.record(staff, "ORGANISATION_ONBOARDING_APPROVED", "ONBOARDING_REQUEST", requestId.toString(),
                    null, "DENIED");
            throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                    "A different Garuda administrator must approve this request.");
        }
        UUID tenantId = UUID.randomUUID();
        String token = AccountTokens.newToken();
        Message message;
        try {
            message = TenantContext.callAs(tenantId, () -> tx.execute(s -> {
                staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
                if (slugTaken(request.organisationSlug())) {
                    throw ApiException.conflict("SLUG_TAKEN", "Another organisation already uses this sign-in code.");
                }
                int updated = jdbc.sql("""
                        UPDATE app.organisation_onboarding_request
                        SET status = 'EXECUTED', approved_by = :staff, executed_at = clock_timestamp(),
                            created_tenant_id = :tenant
                        WHERE id = :id AND status = 'REQUESTED'
                        """).param("staff", staff).param("tenant", tenantId).param("id", requestId).update();
                if (updated != 1) {
                    throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
                }
                jdbc.sql("INSERT INTO app.tenant (id, slug, name, status) VALUES (:id, :slug, :name, 'ACTIVE')")
                        .param("id", tenantId).param("slug", request.organisationSlug())
                        .param("name", request.organisationName()).update();
                UUID adminId = jdbc.sql("""
                        INSERT INTO app.app_user (tenant_id, email, display_name, role, status)
                        VALUES (:tenant, :email, :name, 'ORG_ADMIN', 'INVITED') RETURNING id
                        """).param("tenant", tenantId).param("email", request.adminEmail())
                        .param("name", request.adminDisplayName()).query(UUID.class).single();
                UUID invitationId = jdbc.sql("""
                        INSERT INTO app.user_invitation (tenant_id, user_id, purpose, token_sha256, created_by_staff,
                                                         expires_at)
                        VALUES (:tenant, :user, 'INVITE', :hash, :staff, now() + interval '72 hours')
                        RETURNING id
                        """).param("tenant", tenantId).param("user", adminId).param("hash", AccountTokens.hash(token))
                        .param("staff", staff).query(UUID.class).single();
                return new Message(tenantId, invitationId, Kind.INVITE, request.organisationName(),
                        request.organisationSlug(), request.adminEmail(), request.adminDisplayName(), token);
            }));
        } catch (DataAccessException e) {
            platformAudit.record(staff, "ORGANISATION_ONBOARDING_APPROVED", "ONBOARDING_REQUEST", requestId.toString(),
                    null, "DENIED");
            throw ApiException.conflict("ONBOARDING_REFUSED", "The organisation could not be created: "
                    + Optional.ofNullable(e.getMostSpecificCause().getMessage()).map(m -> m.split("\n")[0]).orElse(""));
        }
        platformAudit.record(staff, "ORGANISATION_CREATED", "TENANT", tenantId.toString(), tenantId, "SUCCESS");
        String emailStatus = accountMail.send(message);
        return new Approved(find(requestId), accountMail.link(message), emailStatus);
    }

    OnboardingRequest decideOnboarding(UUID requestId, boolean cancel, String note) {
        String text = note == null ? null : note.strip();
        if (text != null && text.length() > 2000) {
            throw ApiException.validation("The note is too long.");
        }
        UUID staff = tx.execute(s -> {
            UUID me = staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
            int updated = jdbc.sql("""
                    UPDATE app.organisation_onboarding_request
                    SET status = :status, decided_by = :staff, decided_at = clock_timestamp(), decision_note = :note
                    WHERE id = :id AND status = 'REQUESTED' AND (:cancel = false OR requested_by = :staff)
                    """).param("status", cancel ? "CANCELLED" : "REJECTED").param("staff", me).param("note", text)
                    .param("id", requestId).param("cancel", cancel).update();
            if (updated != 1) {
                throw ApiException.conflict("REQUEST_STATE",
                        cancel ? "Only the requester can cancel an open request." : "This request is already decided.");
            }
            return me;
        });
        platformAudit.record(staff, cancel ? "ORGANISATION_ONBOARDING_CANCELLED" : "ORGANISATION_ONBOARDING_REJECTED",
                "ONBOARDING_REQUEST", requestId.toString(), null, "SUCCESS");
        return find(requestId);
    }

    // --- suspension and reactivation (FR-ORG-03) ------------------------------------------------------------------

    List<StatusRequest> listStatusRequests() {
        return tx.execute(s -> {
            staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
            return jdbc.sql(STATUS_SELECT + " ORDER BY r.status <> 'REQUESTED', r.requested_at DESC LIMIT 200")
                    .query((rs, n) -> status(rs)).list();
        });
    }

    StatusRequest requestStatusChange(UUID tenantId, String kind, String reason) {
        String text = reason == null ? "" : reason.strip();
        if (!"SUSPEND".equals(kind) && !"REACTIVATE".equals(kind)) {
            throw ApiException.validation("Choose SUSPEND or REACTIVATE.");
        }
        if (text.length() < 10 || text.length() > 2000) {
            throw ApiException.validation("Give a reason of 10 to 2000 characters.");
        }
        UUID id;
        try {
            id = tx.execute(s -> {
                UUID staff = staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
                String status = jdbc.sql("SELECT status FROM app.tenant WHERE id = :id").param("id", tenantId)
                        .query(String.class).optional().orElseThrow(ApiException::notFound);
                if (!status.equals("SUSPEND".equals(kind) ? "ACTIVE" : "SUSPENDED")) {
                    throw ApiException.conflict("ORGANISATION_STATE", "SUSPEND".equals(kind)
                            ? "Only an active organisation can be suspended."
                            : "Only a suspended organisation can be reactivated.");
                }
                return jdbc.sql("""
                        INSERT INTO app.organisation_status_request (subject_tenant_id, kind, reason, requested_by)
                        VALUES (:tenant, :kind, :reason, :staff) RETURNING id
                        """).param("tenant", tenantId).param("kind", kind).param("reason", text).param("staff", staff)
                        .query(UUID.class).single();
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("STATUS_REQUEST_OPEN", "This organisation already has an open request.");
        }
        platformAudit.record(current(), "ORGANISATION_" + kind + "_REQUESTED", "STATUS_REQUEST", id.toString(), tenantId,
                "SUCCESS");
        return findStatus(id);
    }

    StatusRequest approveStatusChange(UUID requestId) {
        StatusRequest request = findStatus(requestId);
        UUID staff = tx.execute(s -> {
            UUID me = staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
            if (!"REQUESTED".equals(request.status())) {
                throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
            }
            if (me.equals(request.requestedBy())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                        "A different Garuda administrator must approve this request.");
            }
            jdbc.sql("""
                    UPDATE app.organisation_status_request SET status = 'EXECUTED', approved_by = :staff,
                           executed_at = clock_timestamp()
                    WHERE id = :id AND status = 'REQUESTED'
                    """).param("staff", me).param("id", requestId).update();
            jdbc.sql("UPDATE app.tenant SET status = :status WHERE id = :id")
                    .param("status", "SUSPEND".equals(request.kind()) ? "SUSPENDED" : "ACTIVE")
                    .param("id", request.tenantId()).update();
            return me;
        });
        platformAudit.record(staff, "SUSPEND".equals(request.kind()) ? "ORGANISATION_SUSPENDED" : "ORGANISATION_REACTIVATED",
                "TENANT", request.tenantId().toString(), request.tenantId(), "SUCCESS");
        return findStatus(requestId);
    }

    StatusRequest decideStatusChange(UUID requestId, boolean cancel) {
        UUID staff = tx.execute(s -> {
            UUID me = staffAccess.requirePlatformCapability(StaffCapability.ORG_ONBOARD);
            int updated = jdbc.sql("""
                    UPDATE app.organisation_status_request
                    SET status = :status, decided_by = :staff, decided_at = clock_timestamp()
                    WHERE id = :id AND status = 'REQUESTED' AND (:cancel = false OR requested_by = :staff)
                    """).param("status", cancel ? "CANCELLED" : "REJECTED").param("staff", me).param("id", requestId)
                    .param("cancel", cancel).update();
            if (updated != 1) {
                throw ApiException.conflict("REQUEST_STATE",
                        cancel ? "Only the requester can cancel an open request." : "This request is already decided.");
            }
            return me;
        });
        platformAudit.record(staff, cancel ? "ORGANISATION_STATUS_CANCELLED" : "ORGANISATION_STATUS_REJECTED",
                "STATUS_REQUEST", requestId.toString(), null, "SUCCESS");
        return findStatus(requestId);
    }

    // --- internals ------------------------------------------------------------------------------------------------

    private static final String ONBOARDING_SELECT = """
            SELECT r.id, r.organisation_name, r.organisation_slug, r.admin_email, r.admin_display_name, r.status,
                   r.requested_by, rq.display_name, r.requested_at, r.approved_by, ap.display_name, r.executed_at,
                   r.created_tenant_id, r.decision_note
            FROM app.organisation_onboarding_request r
            JOIN app.staff_user rq ON rq.id = r.requested_by
            LEFT JOIN app.staff_user ap ON ap.id = r.approved_by
            """;

    private static final String STATUS_SELECT = """
            SELECT r.id, r.subject_tenant_id, t.name, r.kind, r.status, r.reason, r.requested_by, rq.display_name,
                   r.requested_at, r.approved_by, r.executed_at
            FROM app.organisation_status_request r
            JOIN app.tenant t ON t.id = r.subject_tenant_id
            JOIN app.staff_user rq ON rq.id = r.requested_by
            """;

    private OnboardingRequest find(UUID id) {
        return tx.execute(s -> jdbc.sql(ONBOARDING_SELECT + " WHERE r.id = :id").param("id", id)
                .query((rs, n) -> onboarding(rs)).optional().orElseThrow(ApiException::notFound));
    }

    private StatusRequest findStatus(UUID id) {
        return tx.execute(s -> jdbc.sql(STATUS_SELECT + " WHERE r.id = :id").param("id", id)
                .query((rs, n) -> status(rs)).optional().orElseThrow(ApiException::notFound));
    }

    private boolean slugTaken(String slug) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.tenant WHERE lower(slug) = :slug)").param("slug", slug)
                .query(Boolean.class).single();
    }

    private boolean nameTaken(String name) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.tenant WHERE name = :name)").param("name", name)
                .query(Boolean.class).single();
    }

    private static UUID current() {
        return TenantContext.currentActor().orElse(null);
    }

    private static OnboardingRequest onboarding(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OnboardingRequest(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, UUID.class), rs.getString(11),
                rs.getObject(12, OffsetDateTime.class), rs.getObject(13, UUID.class), rs.getString(14));
    }

    private static StatusRequest status(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StatusRequest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, UUID.class),
                rs.getObject(11, OffsetDateTime.class));
    }
}
