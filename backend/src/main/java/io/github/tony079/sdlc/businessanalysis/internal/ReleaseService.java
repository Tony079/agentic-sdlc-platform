package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiError.FieldError;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.StaffAccess;
import io.github.tony079.sdlc.identity.api.StaffAccess.StaffContext;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.SecurityEventType;
import io.github.tony079.sdlc.securitymonitoring.api.SecurityEventRecorder.Severity;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual release approval (owner decision O-16, 2026-11-08). A Release Manager (RELEASE_APPROVE, active tenant)
 * approves the release of one internally approved BRD version, bound to its content hash and pinned commit, only when
 * every mandatory gate passes ({@link ReleaseGates}); the approver may not be the internal approver or a correction
 * author of the version (also enforced by the database). Approval and execution are separate: this records the
 * approval and publishes ReleaseApproved; the worker performs T25 after re-checking the gates.
 */
@Service
@Profile("!migrate & !provision")
class ReleaseService {

    record PendingRelease(UUID requirementId, String requirementKey, UUID brdVersionId, int versionNo, String contentHash,
                          String commitSha, OffsetDateTime approvedInternallyAt, List<ReleaseGates.Gate> gates) {
    }

    record ReleaseView(UUID releaseApprovalId, String releaseLabel, String status, List<ReleaseGates.Gate> gates) {
    }

    private final ReviewRepository reviews;
    private final ReleaseGates gates;
    private final StaffAccess staffAccess;
    private final OutboxPublisher outbox;
    private final AuditWriter audit;
    private final SecurityEventRecorder securityEvents;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    ReleaseService(ReviewRepository reviews, ReleaseGates gates, StaffAccess staffAccess, OutboxPublisher outbox,
                   AuditWriter audit, SecurityEventRecorder securityEvents, JdbcClient jdbc, JsonMapper json) {
        this.reviews = reviews;
        this.gates = gates;
        this.staffAccess = staffAccess;
        this.outbox = outbox;
        this.audit = audit;
        this.securityEvents = securityEvents;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<PendingRelease> pending() {
        staffAccess.requireCapability(StaffCapability.RELEASE_APPROVE);
        return jdbc.sql("""
                SELECT v.id FROM app.brd_version v
                WHERE v.status = 'APPROVED_INTERNAL'
                  AND NOT EXISTS (SELECT 1 FROM app.release_approval r WHERE r.brd_version_id = v.id
                                  AND r.status IN ('APPROVED', 'EXECUTED'))
                ORDER BY v.status_at
                """).query(UUID.class).list().stream().map(id -> {
                    VersionRow v = reviews.version(id, false).orElseThrow();
                    return new PendingRelease(v.requirementId(), reviews.requirementKey(v.requirementId()), v.id(),
                            v.versionNo(), v.contentHash(), gates.commitOf(v), approvedAt(v.id()), gates.evaluate(v));
                }).toList();
    }

    @Transactional
    public ReleaseView approve(UUID versionId, String expectedContentHash, String expectedCommitSha, String reason) {
        StaffContext staff = staffAccess.requireCapability(StaffCapability.RELEASE_APPROVE);
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 10 || text.length() > 2000) {
            throw ApiException.validation(List.of(new FieldError("reason", "INVALID_LENGTH")));
        }
        VersionRow version = reviews.version(versionId, true).orElseThrow(ApiException::notFound);
        String commit = gates.commitOf(version);
        if (!version.contentHash().equals(expectedContentHash) || !commit.equals(expectedCommitSha)) {
            throw ApiException.conflict("VERSION_CHANGED",
                    "The version or its commit is not the one shown. Reload before approving the release.");
        }
        // Separation of duties (also a database trigger): not the internal approver, not a correction author.
        boolean internalApprover = jdbc.sql("""
                SELECT count(*) FROM app.approval WHERE brd_version_id = :v AND approver_staff_id = :s
                """).param("v", versionId).param("s", staff.staffId()).query(Integer.class).single() > 0;
        if (internalApprover || version.correctionsJson().contains(staff.staffId().toString())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SEPARATION_OF_DUTIES",
                    "You reviewed or shaped this version; another Release Manager must approve its release.");
        }
        List<ReleaseGates.Gate> result = gates.evaluate(version);
        if (!ReleaseGates.allPassed(result)) {
            audit.recordIndependently(AuditAction.RELEASE_GATE_FAILED, "BRD_VERSION", versionId, AuditResult.DENIED);
            securityEvents.record(SecurityEventType.RELEASE_GATE_FAILED, Severity.MEDIUM, staff.staffId().toString(),
                    versionId.toString());
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_GATE_FAILED",
                    "A mandatory release gate failed; the release cannot be approved.",
                    result.stream().filter(g -> !g.passed()).map(g -> new FieldError(g.name(), "FAILED")).toList());
        }
        if (jdbc.sql("""
                SELECT count(*) FROM app.release_approval WHERE brd_version_id = :v AND status IN ('APPROVED', 'EXECUTED')
                """).param("v", versionId).query(Integer.class).single() > 0) {
            throw ApiException.conflict("RELEASE_ALREADY_APPROVED", "This version's release is already approved.");
        }
        String label = "brd-v" + version.versionNo();
        UUID id = jdbc.sql("""
                INSERT INTO app.release_approval (tenant_id, brd_version_id, requirement_id, release_label, content_hash,
                                                  commit_sha, approver_staff_id, reason, evidence)
                VALUES (:t, :v, :r, :label, :hash, :commit, :staff, :reason, CAST(:evidence AS jsonb)) RETURNING id
                """).param("t", staff.tenantId()).param("v", versionId).param("r", version.requirementId())
                .param("label", label).param("hash", version.contentHash()).param("commit", commit)
                .param("staff", staff.staffId()).param("reason", text)
                .param("evidence", json.writeValueAsString(result)).query(UUID.class).single();
        outbox.publish("REQUIREMENT", version.requirementId(), EventTypes.RELEASE_APPROVED,
                Map.of("requirementId", version.requirementId().toString(), "releaseApprovalId", id.toString()));
        audit.record(AuditAction.RELEASE_APPROVED, "BRD_VERSION", versionId, AuditResult.SUCCESS);
        return new ReleaseView(id, label, "APPROVED", result);
    }

    private OffsetDateTime approvedAt(UUID versionId) {
        return jdbc.sql("""
                SELECT max(decided_at) FROM app.approval WHERE brd_version_id = :v AND decision = 'APPROVED'
                """).param("v", versionId).query(OffsetDateTime.class).optional().orElse(null);
    }
}
