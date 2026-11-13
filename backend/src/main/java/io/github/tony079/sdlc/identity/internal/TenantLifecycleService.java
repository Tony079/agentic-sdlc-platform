package io.github.tony079.sdlc.identity.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.api.StaffPrincipal;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant soft deletion and restoration (owner decision O-25, 2026-11-08).
 * <ol>
 *   <li>A Client Approver of the tenant requests deletion with a reason.</li>
 *   <li>A staff member with TENANT_DELETE, assigned to that tenant, verifies it.</li>
 *   <li>A second, different TENANT_DELETE holder approves it; the approval executes it: the tenant becomes DELETED.
 *       The tenant row and all its data stay; nothing is physically erased; logins, sessions and staff access stop at
 *       once; the worker cancels the tenant's jobs and processes nothing more for it.</li>
 * </ol>
 * Restoration is the reverse, requested by one TENANT_DELETE holder and verified and approved by two others. Every
 * step is in the platform audit and the security event log. The database enforces the same two-person rule
 * (changeset 0025) independently of this class.
 */
@Service
@Profile("!migrate & !provision & !retention")
class TenantLifecycleService {

    record RequestView(UUID id, UUID tenantId, String tenantName, String kind, String status, String reason,
                       OffsetDateTime requestedAt, UUID verifiedBy, OffsetDateTime verifiedAt, UUID approvedBy,
                       OffsetDateTime executedAt) {
    }

    private final JdbcClient jdbc;
    private final StaffAccess staffAccess;
    private final SecurityEventRecorder securityEvents;

    TenantLifecycleService(JdbcClient jdbc, StaffAccess staffAccess, SecurityEventRecorder securityEvents) {
        this.jdbc = jdbc;
        this.staffAccess = staffAccess;
        this.securityEvents = securityEvents;
    }

    // ---- client side ----------------------------------------------------------------------------------------

    @Transactional
    public RequestView requestDeletion(String reason) {
        CurrentUser user = clientApprover();
        String text = reason(reason);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO app.tenant_lifecycle_request (subject_tenant_id, kind, reason, requested_by_user)
                    VALUES (:tenant, 'DELETE', :reason, :user) RETURNING id
                    """).param("tenant", user.tenantId()).param("reason", text).param("user", user.userId())
                    .query(UUID.class).single();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("LIFECYCLE_REQUEST_OPEN", "A deletion request for this organisation is already open.");
        }
        audit(null, "TENANT_DELETION_REQUESTED", id, user.tenantId());
        securityEvents.record(SecurityEventType.TENANT_DELETION_REQUESTED, Severity.HIGH, user.userId().toString(),
                id.toString());
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<RequestView> clientRequests() {
        CurrentUser user = clientApprover();
        return jdbc.sql(SELECT + " WHERE r.subject_tenant_id = :tenant ORDER BY r.requested_at DESC")
                .param("tenant", user.tenantId()).query(TenantLifecycleService::view).list();
    }

    // ---- staff side -----------------------------------------------------------------------------------------

    /** Requests for tenants the caller is assigned to, when the caller holds TENANT_DELETE. */
    @Transactional(readOnly = true)
    public List<RequestView> staffRequests() {
        UUID staff = staffId();
        return jdbc.sql(SELECT + """
                 WHERE app.staff_may_delete_tenant(:staff, r.subject_tenant_id) ORDER BY r.requested_at DESC
                """).param("staff", staff).query(TenantLifecycleService::view).list();
    }

    @Transactional
    public RequestView requestRestoration(UUID tenantId, String reason) {
        UUID staff = staffAccess.requireCapabilityForTenant(StaffCapability.TENANT_DELETE, tenantId);
        String text = reason(reason);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO app.tenant_lifecycle_request (subject_tenant_id, kind, reason, requested_by_staff)
                    VALUES (:tenant, 'RESTORE', :reason, :staff) RETURNING id
                    """).param("tenant", tenantId).param("reason", text).param("staff", staff)
                    .query(UUID.class).single();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("LIFECYCLE_REQUEST_REFUSED",
                    "Restoration needs a deleted organisation and no other open request.");
        } catch (org.springframework.dao.DataAccessException e) {
            throw ApiException.conflict("LIFECYCLE_REQUEST_REFUSED",
                    "Restoration needs a deleted organisation and no other open request.");
        }
        audit(staff, "TENANT_RESTORATION_REQUESTED", id, tenantId);
        securityEvents.record(SecurityEventType.TENANT_DELETION_REQUESTED, Severity.HIGH, staff.toString(), id.toString());
        return find(id);
    }

    @Transactional
    public RequestView verify(UUID requestId) {
        RequestView request = open(requestId, "REQUESTED");
        UUID staff = staffAccess.requireCapabilityForTenant(StaffCapability.TENANT_DELETE, request.tenantId());
        if (staff.equals(requestedByStaff(requestId))) {
            throw forbidden("SELF_VERIFICATION_FORBIDDEN", "The requester cannot verify their own request.");
        }
        jdbc.sql("""
                UPDATE app.tenant_lifecycle_request SET status = 'VERIFIED', verified_by = :staff, verified_at = clock_timestamp()
                WHERE id = :id AND status = 'REQUESTED'
                """).param("staff", staff).param("id", requestId).update();
        audit(staff, "TENANT_LIFECYCLE_VERIFIED", requestId, request.tenantId());
        return find(requestId);
    }

    /** Second approval: executes the soft deletion or restoration in the same transaction. */
    @Transactional
    public RequestView approve(UUID requestId) {
        RequestView request = open(requestId, "VERIFIED");
        UUID staff = staffAccess.requireCapabilityForTenant(StaffCapability.TENANT_DELETE, request.tenantId());
        if (staff.equals(request.verifiedBy()) || staff.equals(requestedByStaff(requestId))) {
            throw forbidden("SECOND_APPROVER_REQUIRED", "A second, different authorised person must approve.");
        }
        jdbc.sql("""
                UPDATE app.tenant_lifecycle_request SET status = 'EXECUTED', approved_by = :staff,
                       executed_at = clock_timestamp()
                WHERE id = :id AND status = 'VERIFIED'
                """).param("staff", staff).param("id", requestId).update();
        boolean delete = "DELETE".equals(request.kind());
        jdbc.sql("UPDATE app.tenant SET status = :status, status_changed_at = clock_timestamp() WHERE id = :tenant")
                .param("status", delete ? "DELETED" : "ACTIVE").param("tenant", request.tenantId()).update();
        audit(staff, delete ? "TENANT_SOFT_DELETED" : "TENANT_RESTORED", requestId, request.tenantId());
        securityEvents.record(delete ? SecurityEventType.TENANT_SOFT_DELETED : SecurityEventType.TENANT_RESTORED,
                Severity.HIGH, staff.toString(), request.tenantId().toString());
        return find(requestId);
    }

    @Transactional
    public RequestView reject(UUID requestId, String note) {
        RequestView request = find(requestId);
        if (!"REQUESTED".equals(request.status()) && !"VERIFIED".equals(request.status())) {
            throw ApiException.conflict("LIFECYCLE_REQUEST_FINAL", "This request is already decided.");
        }
        UUID staff = staffAccess.requireCapabilityForTenant(StaffCapability.TENANT_DELETE, request.tenantId());
        jdbc.sql("""
                UPDATE app.tenant_lifecycle_request SET status = 'REJECTED', decided_by = :staff, decision_note = :note
                WHERE id = :id
                """).param("staff", staff).param("note", note == null ? null : note.strip()).param("id", requestId)
                .update();
        audit(staff, "TENANT_LIFECYCLE_REJECTED", requestId, request.tenantId());
        return find(requestId);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT r.id, r.subject_tenant_id, t.name, r.kind, r.status, r.reason, r.requested_at, r.verified_by, r.verified_at,
                   r.approved_by, r.executed_at
            FROM app.tenant_lifecycle_request r JOIN app.tenant t ON t.id = r.subject_tenant_id
            """;

    private static RequestView view(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new RequestView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class),
                rs.getObject(8, UUID.class), rs.getObject(9, OffsetDateTime.class), rs.getObject(10, UUID.class),
                rs.getObject(11, OffsetDateTime.class));
    }

    private RequestView find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(TenantLifecycleService::view).optional()
                .orElseThrow(ApiException::notFound);
    }

    /** Not found unless the caller may act on the tenant (no disclosure of other tenants' requests). */
    private RequestView open(UUID id, String expectedStatus) {
        RequestView request = find(id);
        staffAccess.requireCapabilityForTenant(StaffCapability.TENANT_DELETE, request.tenantId());
        if (!expectedStatus.equals(request.status())) {
            throw ApiException.conflict("LIFECYCLE_REQUEST_STATE", "This request is " + request.status() + ".");
        }
        return request;
    }

    private UUID requestedByStaff(UUID id) {
        return jdbc.sql("SELECT requested_by_staff FROM app.tenant_lifecycle_request WHERE id = :id").param("id", id)
                .query(UUID.class).optional().orElse(null);
    }

    private static CurrentUser clientApprover() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)
                || !user.orgAdmin()) {
            throw forbidden("FORBIDDEN", "Only an Organisation Admin can request deletion of the organisation.");
        }
        return user;
    }

    private static UUID staffId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StaffPrincipal principal)) {
            throw forbidden("FORBIDDEN", "Not allowed.");
        }
        return principal.staffId();
    }

    private static String reason(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 10 || text.length() > 2000) {
            throw ApiException.validation(List.of(new FieldError("reason", "INVALID_LENGTH")));
        }
        return text;
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private void audit(UUID staff, String action, UUID requestId, UUID tenant) {
        jdbc.sql("""
                INSERT INTO app.platform_audit_event (actor_staff_id, action, target_type, target_ref, tenant_ref, result)
                VALUES (:actor, :action, 'TENANT_LIFECYCLE_REQUEST', :ref, :tenant, 'SUCCESS')
                """).param("actor", staff).param("action", action).param("ref", requestId.toString())
                .param("tenant", tenant).update();
    }
}
