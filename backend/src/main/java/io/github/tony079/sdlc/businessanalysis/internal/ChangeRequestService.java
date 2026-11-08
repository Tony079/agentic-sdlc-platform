package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;
import io.github.tony079.sdlc.requirement.api.ScopeRevisions;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client change requests (owner decision C-09, 2026-11-08) with the prioritised scope diff (C-16).
 * <ol>
 *   <li>The requirement owner submits a change against the released, approved scope (SHARED_WITH_CLIENT): requested
 *       change, justification, expected impact, and optionally the proposed scope fields. Submission changes nothing.</li>
 *   <li>A Business Analyst (BUSINESS_ANALYST) assesses scope, effort, timeline and technical impact, and may adjust a
 *       diff priority with a justification.</li>
 *   <li>A Business Reviewer (BUSINESS_REVIEWER), not the assessor, approves the assessment or returns it.</li>
 *   <li>A Client Approver of the tenant, not the submitter, accepts or declines. The approver sees the change request
 *       and its diff only (not the rest of the requirement) and only once it awaits their decision — the narrowest
 *       disclosure compatible with C-18 owner-only visibility.</li>
 *   <li>Acceptance creates a new approved-scope version and a new requirement revision, then T27; the worker re-analyses
 *       (T28), which supersedes the released BRD version and invalidates its approvals. Previous versions are kept.</li>
 * </ol>
 * No model takes part in any decision.
 */
@Service
@Profile("!migrate & !provision")
class ChangeRequestService {

    record DiffView(String field, String area, String changeType, String recommendedPriority, String priority,
                    String before, String after, String adjustmentReason) {
    }

    record ChangeRequestView(UUID id, UUID requirementId, String status, String requestedChange, String justification,
                             String expectedImpact, OffsetDateTime submittedAt, String scopeAssessment,
                             String effortEstimate, String timelineImpact, String technicalImpact, String reviewNote,
                             String decisionNote, int baselineVersion, List<DiffView> diff) {
    }

    private final JdbcClient jdbc;
    private final StaffAccess staffAccess;
    private final RequirementDirectory requirements;
    private final ScopeRevisions scope;
    private final WorkflowEngine workflow;
    private final ReviewRepository reviews;
    private final AuditWriter audit;
    private final JsonMapper json;

    ChangeRequestService(JdbcClient jdbc, StaffAccess staffAccess, RequirementDirectory requirements,
                         ScopeRevisions scope, WorkflowEngine workflow, ReviewRepository reviews, AuditWriter audit,
                         JsonMapper json) {
        this.jdbc = jdbc;
        this.staffAccess = staffAccess;
        this.requirements = requirements;
        this.scope = scope;
        this.workflow = workflow;
        this.reviews = reviews;
        this.audit = audit;
        this.json = json;
    }

    // ---- owner -------------------------------------------------------------------------------------------------

    @Transactional
    public ChangeRequestView submit(UUID requirementId, String requestedChange, String justification,
                                    String expectedImpact, Map<String, String> proposed) {
        requireText("requestedChange", requestedChange, 10, 5000);
        requireText("justification", justification, 10, 5000);
        requireText("expectedImpact", expectedImpact, 1, 5000);
        if (requirements.findOwned(requirementId).isEmpty()) {
            throw ApiException.notFound();
        }
        if (workflow.lockState(requirementId) != WorkflowState.SHARED_WITH_CLIENT) {
            throw ApiException.conflict("NO_APPROVED_SCOPE", "Changes can be requested only on a released, approved scope.");
        }
        UUID tenant = TenantContext.requireTenant();
        UUID user = TenantContext.currentActor().orElseThrow();
        Baseline baseline = baseline(requirementId, tenant);
        Content proposedContent = merge(baseline.content(), proposed);
        List<ScopeDiff.Change> changes = ScopeDiff.compare(baseline.content(), proposedContent);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO app.change_request (tenant_id, requirement_id, baseline_scope_id, requested_change,
                        justification, expected_impact, proposed_content, proposed_hash, submitted_by)
                    VALUES (:t, :r, :b, :c, :j, :e, CAST(:p AS jsonb), :h, :u) RETURNING id
                    """).param("t", tenant).param("r", requirementId).param("b", baseline.id())
                    .param("c", requestedChange.strip()).param("j", justification.strip())
                    .param("e", expectedImpact.strip()).param("p", json.writeValueAsString(proposedContent))
                    .param("h", scope.hash(proposedContent)).param("u", user).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CHANGE_REQUEST_OPEN", "A change request for this requirement is already open.");
        }
        for (ScopeDiff.Change c : changes) {
            jdbc.sql("""
                    INSERT INTO app.change_request_diff (tenant_id, change_request_id, field, area, change_type,
                        recommended_priority, priority, before_value, after_value)
                    VALUES (:t, :cr, :f, :a, :type, :p, :p, :b, :after)
                    """).param("t", tenant).param("cr", id).param("f", c.field()).param("a", c.area())
                    .param("type", c.changeType()).param("p", c.priority()).param("b", c.before())
                    .param("after", c.after()).update();
        }
        audit.record(AuditAction.CHANGE_REQUEST_SUBMITTED, "CHANGE_REQUEST", id, AuditResult.SUCCESS);
        return view(id);
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestView> ownerList(UUID requirementId) {
        if (requirements.findOwned(requirementId).isEmpty()) {
            throw ApiException.notFound();
        }
        return jdbc.sql("SELECT id FROM app.change_request WHERE requirement_id = :r ORDER BY submitted_at DESC")
                .param("r", requirementId).query(UUID.class).list().stream().map(this::view).toList();
    }

    // ---- staff -------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChangeRequestView> staffQueue() {
        StaffContext staff = staffAccess.requireActiveTenant();
        if (!hasAny(staff, StaffCapability.BUSINESS_ANALYST) && !hasAny(staff, StaffCapability.BUSINESS_REVIEWER)) {
            staffAccess.requireCapability(StaffCapability.BUSINESS_ANALYST);
        }
        return jdbc.sql("""
                SELECT id FROM app.change_request WHERE status IN ('SUBMITTED', 'ASSESSED') ORDER BY submitted_at
                """).query(UUID.class).list().stream().map(this::view).toList();
    }

    @Transactional
    public ChangeRequestView assess(UUID id, String scopeAssessment, String effort, String timeline, String technical) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.BUSINESS_ANALYST);
        requireText("scopeAssessment", scopeAssessment, 10, 5000);
        requireText("effortEstimate", effort, 1, 1000);
        requireText("timelineImpact", timeline, 1, 1000);
        requireText("technicalImpact", technical, 1, 5000);
        status(id, Set.of("SUBMITTED", "ASSESSED"));
        jdbc.sql("""
                UPDATE app.change_request SET status = 'ASSESSED', assessed_by = :s, assessed_at = clock_timestamp(),
                       scope_assessment = :a, effort_estimate = :e, timeline_impact = :tl, technical_impact = :tc
                WHERE id = :id
                """).param("s", staff.staffId()).param("a", scopeAssessment.strip()).param("e", effort.strip())
                .param("tl", timeline.strip()).param("tc", technical.strip()).param("id", id).update();
        audit.record(AuditAction.CHANGE_REQUEST_ASSESSED, "CHANGE_REQUEST", id, AuditResult.SUCCESS);
        return view(id);
    }

    /** C-16: a Business Analyst changes one item's priority, with a mandatory justification. */
    @Transactional
    public ChangeRequestView adjustPriority(UUID id, String field, String priority, String reason) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.BUSINESS_ANALYST);
        if (!ScopeDiff.validPriority(priority)) {
            throw ApiException.validation(List.of(new FieldError("priority", "INVALID")));
        }
        requireText("reason", reason, 10, 2000);
        status(id, Set.of("SUBMITTED", "ASSESSED"));
        int updated = jdbc.sql("""
                UPDATE app.change_request_diff SET priority = :p, adjusted_by = :s, adjustment_reason = :r
                WHERE change_request_id = :id AND field = :f
                """).param("p", priority.toUpperCase(Locale.ROOT)).param("s", staff.staffId()).param("r", reason.strip())
                .param("id", id).param("f", field).update();
        if (updated == 0) {
            throw ApiException.notFound();
        }
        audit.record(AuditAction.CHANGE_PRIORITY_ADJUSTED, "CHANGE_REQUEST", id, AuditResult.SUCCESS);
        return view(id);
    }

    @Transactional
    public ChangeRequestView review(UUID id, boolean approve, String note) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.BUSINESS_REVIEWER);
        status(id, Set.of("ASSESSED"));
        UUID assessor = jdbc.sql("SELECT assessed_by FROM app.change_request WHERE id = :id").param("id", id)
                .query(UUID.class).single();
        if (staff.staffId().equals(assessor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SEPARATION_OF_DUTIES",
                    "The assessor cannot also review the assessment.");
        }
        if (approve) {
            jdbc.sql("""
                    UPDATE app.change_request SET status = 'AWAITING_CLIENT_DECISION', reviewed_by = :s,
                           reviewed_at = clock_timestamp(), review_note = :n WHERE id = :id
                    """).param("s", staff.staffId()).param("n", note).param("id", id).update();
        } else {
            requireText("note", note, 10, 2000);
            jdbc.sql("""
                    UPDATE app.change_request SET status = 'SUBMITTED', review_note = :n, assessed_by = NULL,
                           assessed_at = NULL WHERE id = :id
                    """).param("n", note.strip()).param("id", id).update();
        }
        audit.record(AuditAction.CHANGE_REQUEST_REVIEWED, "CHANGE_REQUEST", id, AuditResult.SUCCESS);
        return view(id);
    }

    // ---- Client Approver ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChangeRequestView> approverQueue() {
        clientApprover();
        return jdbc.sql("""
                SELECT id FROM app.change_request WHERE status IN ('AWAITING_CLIENT_DECISION', 'ACCEPTED', 'DECLINED')
                  AND reviewed_by IS NOT NULL ORDER BY submitted_at DESC
                """).query(UUID.class).list().stream().map(this::view).toList();
    }

    @Transactional
    public ChangeRequestView decide(UUID id, boolean accept, String note) {
        CurrentUser approver = clientApprover();
        record Row(UUID requirementId, UUID submittedBy, String proposed, String status) {
        }
        Row row = jdbc.sql("""
                SELECT requirement_id, submitted_by, proposed_content::text, status FROM app.change_request
                WHERE id = :id FOR UPDATE
                """).param("id", id).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4))).optional().orElseThrow(ApiException::notFound);
        if (!"AWAITING_CLIENT_DECISION".equals(row.status())) {
            throw ApiException.conflict("CHANGE_REQUEST_STATE", "This change request does not await a decision.");
        }
        if (approver.userId().equals(row.submittedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "The submitter cannot accept their own change request.");
        }
        if (!accept) {
            requireText("note", note, 10, 2000);
            jdbc.sql("""
                    UPDATE app.change_request SET status = 'DECLINED', decided_by = :u, decided_at = clock_timestamp(),
                           decision_note = :n WHERE id = :id
                    """).param("u", approver.userId()).param("n", note.strip()).param("id", id).update();
            audit.record(AuditAction.CHANGE_REQUEST_DECLINED, "CHANGE_REQUEST", id, AuditResult.SUCCESS);
            return view(id);
        }
        if (workflow.lockState(row.requirementId()) != WorkflowState.SHARED_WITH_CLIENT) {
            throw ApiException.conflict("NO_APPROVED_SCOPE", "The requirement no longer has a released scope.");
        }
        Content proposed = json.readValue(row.proposed(), Content.class);
        ScopeRevisions.Current current = scope.current(row.requirementId());
        int revision = scope.applyAcceptedScope(row.requirementId(), current.revisionNo(), proposed, row.submittedBy());
        int version = jdbc.sql("SELECT coalesce(max(version_no), 0) + 1 FROM app.approved_scope_version WHERE requirement_id = :r")
                .param("r", row.requirementId()).query(Integer.class).single();
        UUID tenant = TenantContext.requireTenant();
        UUID scopeId = jdbc.sql("""
                INSERT INTO app.approved_scope_version (tenant_id, requirement_id, version_no, content, content_hash,
                    revision_no, source, change_request_id, accepted_by)
                VALUES (:t, :r, :v, CAST(:c AS jsonb), :h, :rev, 'CHANGE_REQUEST', :cr, :u) RETURNING id
                """).param("t", tenant).param("r", row.requirementId()).param("v", version)
                .param("c", row.proposed()).param("h", scope.hash(proposed)).param("rev", revision).param("cr", id)
                .param("u", approver.userId()).query(UUID.class).single();
        jdbc.sql("""
                UPDATE app.change_request SET status = 'ACCEPTED', decided_by = :u, decided_at = clock_timestamp(),
                       decision_note = :n, accepted_scope_id = :s WHERE id = :id
                """).param("u", approver.userId()).param("n", note == null ? null : note.strip()).param("s", scopeId)
                .param("id", id).update();
        // Downstream: the existing client-change path re-analyses (T27 then T28 in the worker) with the request.
        VersionRow released = reviews.latestWithStatus(row.requirementId(), Set.of("RELEASED"))
                .orElseThrow(ApiException::notFound);
        String text = jdbc.sql("SELECT requested_change FROM app.change_request WHERE id = :id").param("id", id)
                .query(String.class).single();
        reviews.insertClientChange(tenant, row.requirementId(), released.id(), text, row.submittedBy());
        workflow.transition(row.requirementId(), Transition.T27_CLIENT_CHANGES, "Change request " + id + " accepted");
        audit.record(AuditAction.CHANGE_REQUEST_ACCEPTED, "CHANGE_REQUEST", id, AuditResult.SUCCESS);
        return view(id);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private record Baseline(UUID id, Content content) {
    }

    /** The latest approved scope; the first one is the released baseline (the current revision of a shared BRD). */
    private Baseline baseline(UUID requirementId, UUID tenant) {
        record Row(UUID id, String content) {
        }
        Row latest = jdbc.sql("""
                SELECT id, content::text FROM app.approved_scope_version WHERE requirement_id = :r
                ORDER BY version_no DESC LIMIT 1
                """).param("r", requirementId).query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional().orElse(null);
        if (latest != null) {
            return new Baseline(latest.id(), json.readValue(latest.content(), Content.class));
        }
        ScopeRevisions.Current current = scope.current(requirementId);
        UUID id = jdbc.sql("""
                INSERT INTO app.approved_scope_version (tenant_id, requirement_id, version_no, content, content_hash,
                    revision_no, source)
                VALUES (:t, :r, 1, CAST(:c AS jsonb), :h, :rev, 'RELEASED_BASELINE') RETURNING id
                """).param("t", tenant).param("r", requirementId).param("c", json.writeValueAsString(current.content()))
                .param("h", current.contentHash()).param("rev", current.revisionNo()).query(UUID.class).single();
        return new Baseline(id, current.content());
    }

    private static Content merge(Content base, Map<String, String> proposed) {
        Map<String, String> p = proposed == null ? Map.of() : proposed;
        for (String key : p.keySet()) {
            if (!ScopeDiff.fields(base).containsKey(key)) {
                throw ApiException.validation(List.of(new FieldError("proposed." + key, "UNKNOWN_FIELD")));
            }
        }
        return new Content(base.kind(), p.getOrDefault("title", base.title()),
                p.getOrDefault("description", base.description()),
                p.getOrDefault("businessObjective", base.businessObjective()),
                p.getOrDefault("expectedOutcomes", base.expectedOutcomes()),
                p.getOrDefault("acceptanceCriteria", base.acceptanceCriteria()), base.repositoryUrl(),
                p.getOrDefault("constraints", base.constraints()), p.getOrDefault("integrations", base.integrations()));
    }

    private void status(UUID id, Set<String> allowed) {
        String status = jdbc.sql("SELECT status FROM app.change_request WHERE id = :id FOR UPDATE").param("id", id)
                .query(String.class).optional().orElseThrow(ApiException::notFound);
        if (!allowed.contains(status)) {
            throw ApiException.conflict("CHANGE_REQUEST_STATE", "This change request is " + status + ".");
        }
    }

    private boolean hasAny(StaffContext staff, StaffCapability capability) {
        return jdbc.sql("""
                SELECT count(*) FROM app.staff_capability_grant WHERE staff_id = :s AND capability = :c AND revoked_at IS NULL
                """).param("s", staff.staffId()).param("c", capability.name()).query(Integer.class).single() > 0;
    }

    private static CurrentUser clientApprover() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)
                || user.role() != CurrentUser.TenantRole.CLIENT_APPROVER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a Client Approver can decide on changes.");
        }
        return user;
    }

    private static void requireText(String field, String value, int min, int max) {
        int length = value == null ? 0 : value.strip().length();
        if (length < min || length > max) {
            throw ApiException.validation(List.of(new FieldError(field, "INVALID_LENGTH")));
        }
    }

    private ChangeRequestView view(UUID id) {
        List<DiffView> diff = jdbc.sql("""
                SELECT field, area, change_type, recommended_priority, priority, before_value, after_value,
                       adjustment_reason FROM app.change_request_diff WHERE change_request_id = :id
                ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, field
                """).param("id", id).query((rs, n) -> new DiffView(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))).list();
        return jdbc.sql("""
                SELECT c.id, c.requirement_id, c.status, c.requested_change, c.justification, c.expected_impact,
                       c.submitted_at, c.scope_assessment, c.effort_estimate, c.timeline_impact, c.technical_impact,
                       c.review_note, c.decision_note, s.version_no
                FROM app.change_request c JOIN app.approved_scope_version s ON s.id = c.baseline_scope_id WHERE c.id = :id
                """).param("id", id).query((rs, n) -> new ChangeRequestView(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getInt(14), diff)).single();
    }

    static Map<String, String> asMap(Content c) {
        return new LinkedHashMap<>(ScopeDiff.fields(c));
    }
}
