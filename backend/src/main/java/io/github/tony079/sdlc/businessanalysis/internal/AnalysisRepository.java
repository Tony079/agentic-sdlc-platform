package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedEvidence;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedItem;
import io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Worker-side data access of the analysis stage (tenant rows, forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class AnalysisRepository {

    record InputSetRow(UUID id, UUID requirementId, int revisionNo, UUID repositoryId, String commitSha,
                       UUID snapshotId, String documentsJson, String correctionsJson, String inputHash,
                       List<SourceRef> sourceRefs) {

        /** The snapshots to read, each with the prefix its paths carry (one unprefixed source for legacy sets). */
        List<RepositoryInputs.Source> sources() {
            return sourceRefs.stream().map(r -> new RepositoryInputs.Source(r.snapshotId(), r.prefix())).toList();
        }

        /** The source a cited path belongs to (longest matching prefix). */
        SourceRef sourceOf(String path) {
            SourceRef best = null;
            for (SourceRef r : sourceRefs) {
                if (path != null && path.startsWith(r.prefix())
                        && (best == null || r.prefix().length() > best.prefix().length())) {
                    best = r;
                }
            }
            return best != null || sourceRefs.isEmpty() ? best : sourceRefs.get(0);
        }
    }

    /** One analysed source (GD-23). */
    record SourceRef(UUID repositoryId, String fullName, String branch, String commitSha, UUID snapshotId,
                     String prefix) {
    }

    record PendingRow(UUID id, UUID requirementId, UUID stageExecutionId, UUID inputSetId, String inputHash,
                      String proposalJson, String understanding, String provider, String modelId, String status) {
    }

    private final JdbcClient jdbc;

    AnalysisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insertInputSet(UUID tenantId, InputSets.Draft draft, String documentsJson) {
        return jdbc.sql("""
                INSERT INTO app.analysis_input_set (tenant_id, requirement_id, revision_no, revision_hash, repository_id,
                                                    commit_sha, snapshot_id, documents, corrections, input_hash, sources)
                VALUES (:tenant, :requirement, :revisionNo, :revisionHash, :repository, :sha, :snapshot,
                        CAST(:documents AS jsonb), CAST(:corrections AS jsonb), :hash, CAST(:sources AS jsonb))
                RETURNING id
                """).param("tenant", tenantId).param("requirement", draft.requirementId())
                .param("revisionNo", draft.revision().revisionNo()).param("revisionHash", draft.revision().contentHash())
                .param("repository", draft.repositories().isEmpty() ? null : draft.repositories().get(0).repositoryId())
                .param("sha", draft.repositories().isEmpty() ? null : draft.repositories().get(0).commitSha())
                .param("snapshot", draft.repositories().isEmpty() ? null : draft.repositories().get(0).snapshotId())
                .param("documents", documentsJson)
                .param("sources", sourcesJson(draft.repositories()))
                .param("corrections", draft.correctionsJson()).param("hash", draft.inputHash())
                .query(UUID.class).single();
    }

    Optional<InputSetRow> inputSet(UUID id) {
        return jdbc.sql("""
                SELECT id, requirement_id, revision_no, repository_id, commit_sha, snapshot_id, documents::text,
                       corrections::text, input_hash, sources::text
                FROM app.analysis_input_set WHERE id = :id
                """).param("id", id)
                .query((rs, n) -> {
                    UUID repo = rs.getObject(4, UUID.class);
                    String sha = rs.getString(5);
                    UUID snapshot = rs.getObject(6, UUID.class);
                    List<SourceRef> refs = parseSources(rs.getString(10));
                    if (refs.isEmpty() && repo != null) { // legacy linked repository; GD-28 new products have none
                        refs = List.of(new SourceRef(repo, null, null, sha, snapshot, ""));
                    }
                    return new InputSetRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                            repo, sha, snapshot, rs.getString(7), rs.getString(8), rs.getString(9), refs);
                })
                .optional();
    }

    /** Tx P: idempotent per stage execution. */
    UUID insertPending(UUID tenantId, UUID requirementId, UUID stage, UUID inputSet, String inputHash, long epoch,
                       String proposal, String understanding, String provider, String modelId) {
        Optional<UUID> inserted = jdbc.sql("""
                INSERT INTO app.ba_result_pending (tenant_id, requirement_id, stage_execution_id, input_set_id, input_hash,
                    lease_epoch, proposal, understanding, model_provider, model_id, prompt_phase_a, prompt_phase_b)
                VALUES (:tenant, :requirement, :stage, :inputSet, :hash, :epoch, CAST(:proposal AS jsonb), :understanding,
                        :provider, :model, :promptA, :promptB)
                ON CONFLICT (stage_execution_id) DO NOTHING
                RETURNING id
                """).param("tenant", tenantId).param("requirement", requirementId).param("stage", stage)
                .param("inputSet", inputSet).param("hash", inputHash).param("epoch", epoch).param("proposal", proposal)
                .param("understanding", understanding).param("provider", provider).param("model", modelId)
                .param("promptA", BaPrompts.ref(BaPrompts.PHASE_A)).param("promptB", BaPrompts.ref(BaPrompts.PHASE_B))
                .query(UUID.class).optional();
        return inserted.orElseGet(() -> jdbc.sql("SELECT id FROM app.ba_result_pending WHERE stage_execution_id = :s")
                .param("s", stage).query(UUID.class).single());
    }

    Optional<PendingRow> pending(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT id, requirement_id, stage_execution_id, input_set_id, input_hash, proposal::text, understanding,
                       model_provider, model_id, status
                FROM app.ba_result_pending WHERE id = :id""" + (lock ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, n) -> new PendingRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)))
                .optional();
    }

    void decidePending(UUID id, String status, String reason) {
        jdbc.sql("""
                UPDATE app.ba_result_pending SET status = :status, reason_code = :reason, decided_at = clock_timestamp()
                WHERE id = :id AND status = 'PENDING'
                """).param("status", status).param("reason", reason).param("id", id).update();
    }

    UUID brdFor(UUID tenantId, UUID requirementId) {
        jdbc.sql("""
                INSERT INTO app.brd (tenant_id, requirement_id) VALUES (:tenant, :requirement)
                ON CONFLICT (tenant_id, requirement_id) DO NOTHING
                """).param("tenant", tenantId).param("requirement", requirementId).update();
        return jdbc.sql("SELECT id FROM app.brd WHERE requirement_id = :requirement").param("requirement", requirementId)
                .query(UUID.class).single();
    }

    /** TDD 19.4: a new BRD version invalidates the approvals of the versions it supersedes. */
    void invalidateApprovalsOfOpenVersions(UUID requirementId) {
        jdbc.sql("""
                UPDATE app.approval SET status = 'INVALIDATED', invalidated_by_event = 'BrdVersionCreated',
                       invalidated_at = clock_timestamp()
                WHERE status = 'VALID' AND brd_version_id IN (SELECT id FROM app.brd_version WHERE requirement_id = :r
                      AND status IN ('IN_REVIEW', 'APPROVED_INTERNAL', 'RELEASED'))
                """).param("r", requirementId).update();
    }

    void supersedeOpenVersions(UUID requirementId) {
        jdbc.sql("""
                UPDATE app.brd_version SET status = 'SUPERSEDED', status_at = clock_timestamp()
                WHERE requirement_id = :requirement AND status IN ('IN_REVIEW', 'APPROVED_INTERNAL', 'RELEASED')
                """).param("requirement", requirementId).update();
    }

    UUID insertVersion(UUID tenantId, UUID brdId, UUID requirementId, PendingRow pending, String contentHash,
                       String excludedJson) {
        return jdbc.sql("""
                INSERT INTO app.brd_version (tenant_id, brd_id, requirement_id, version_no, pending_result_id, input_set_id,
                    input_hash, content_hash, understanding, excluded_documents, status, model_provider, model_id)
                VALUES (:tenant, :brd, :requirement,
                        (SELECT coalesce(max(version_no), 0) + 1 FROM app.brd_version WHERE brd_id = :brd),
                        :pending, :inputSet, :hash, :content, :understanding, CAST(:excluded AS jsonb), 'IN_REVIEW',
                        :provider, :model)
                RETURNING id
                """).param("tenant", tenantId).param("brd", brdId).param("requirement", requirementId)
                .param("pending", pending.id()).param("inputSet", pending.inputSetId()).param("hash", pending.inputHash())
                .param("content", contentHash).param("understanding", pending.understanding())
                .param("excluded", excludedJson).param("provider", pending.provider()).param("model", pending.modelId())
                .query(UUID.class).single();
    }

    void setFeasibility(UUID versionId, String verdict, String reasonsJson) {
        jdbc.sql("UPDATE app.brd_version SET feasibility = :v, feasibility_reasons = CAST(:r AS jsonb) WHERE id = :id")
                .param("v", verdict).param("r", reasonsJson).param("id", versionId).update();
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    static String sourcesJson(List<io.github.tony079.sdlc.sourcerepo.api.RepositoryInputs.PinnedRepository> sources) {
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (var s : sources) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("repositoryId", s.repositoryId().toString());
            m.put("fullName", s.fullName());
            m.put("branch", s.branch());
            m.put("commitSha", s.commitSha());
            m.put("snapshotId", s.snapshotId().toString());
            m.put("prefix", s.prefix());
            out.add(m);
        }
        return JSON.writeValueAsString(out);
    }

    static List<SourceRef> parseSources(String json) {
        List<SourceRef> refs = new java.util.ArrayList<>();
        if (json == null) {
            return refs;
        }
        for (tools.jackson.databind.JsonNode n : JSON.readTree(json)) {
            refs.add(new SourceRef(UUID.fromString(n.path("repositoryId").asString()), n.path("fullName").asString(null),
                    n.path("branch").asString(null), n.path("commitSha").asString(),
                    UUID.fromString(n.path("snapshotId").asString()), n.path("prefix").asString("")));
        }
        return refs;
    }

    /** Each repository citation is attributed to the source its path belongs to (repository and pinned commit). */
    void insertItems(UUID tenantId, UUID versionId, InputSetRow set, List<VerifiedItem> items) {
        for (VerifiedItem item : items) {
            UUID itemId = jdbc.sql("""
                    INSERT INTO app.brd_item (tenant_id, brd_version_id, ordinal, item_key, kind, proposed_kind, section,
                                              statement, risk, component, support_state, rejected_citations)
                    VALUES (:tenant, :version, :ordinal, :key, :kind, :proposed, :section, :statement, :risk, :component,
                            :support, :rejected)
                    RETURNING id
                    """).param("tenant", tenantId).param("version", versionId).param("ordinal", item.ordinal())
                    .param("key", item.itemKey()).param("kind", item.kind().name())
                    .param("proposed", item.proposedKind().name()).param("section", item.section())
                    .param("statement", item.statement()).param("risk", item.risk()).param("component", item.component())
                    .param("support", item.supportState()).param("rejected", item.rejectedCitations())
                    .query(UUID.class).single();
            for (VerifiedEvidence e : item.evidence()) {
                boolean repo = e.type() == BaContracts.EvidenceType.REPOSITORY;
                jdbc.sql("""
                        INSERT INTO app.brd_item_evidence (tenant_id, brd_item_id, type, repository_id, commit_sha, path,
                            start_line, end_line, range_sha256, document_version_id, extraction_no, chunk_ordinal,
                            chunk_label, quote, support_passed)
                        VALUES (:tenant, :item, :type, :repo, :sha, :path, :start, :end, :rangeHash, :docVersion,
                                :extraction, :chunk, :label, :quote, :support)
                        """).param("tenant", tenantId).param("item", itemId).param("type", e.type().name())
                        .param("repo", repo ? set.sourceOf(e.path()).repositoryId() : null)
                        .param("sha", repo ? set.sourceOf(e.path()).commitSha() : null)
                        .param("path", e.path()).param("start", e.startLine()).param("end", e.endLine())
                        .param("rangeHash", e.rangeSha256()).param("docVersion", e.documentVersionId())
                        .param("extraction", e.extractionNo()).param("chunk", e.chunkOrdinal())
                        .param("label", e.chunkLabel()).param("quote", e.quote()).param("support", e.supportPassed())
                        .update();
            }
        }
    }
}
