package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedEvidence;
import io.github.tony079.sdlc.businessanalysis.internal.EvidenceVerifier.VerifiedItem;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Worker-side data access of the analysis stage (tenant rows, forced RLS). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class AnalysisRepository {

    record InputSetRow(UUID id, UUID requirementId, int revisionNo, UUID repositoryId, String commitSha,
                       UUID snapshotId, String documentsJson, String correctionsJson, String inputHash) {
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
                                                    commit_sha, snapshot_id, documents, corrections, input_hash)
                VALUES (:tenant, :requirement, :revisionNo, :revisionHash, :repository, :sha, :snapshot,
                        CAST(:documents AS jsonb), CAST(:corrections AS jsonb), :hash)
                RETURNING id
                """).param("tenant", tenantId).param("requirement", draft.requirementId())
                .param("revisionNo", draft.revision().revisionNo()).param("revisionHash", draft.revision().contentHash())
                .param("repository", draft.repository().repositoryId()).param("sha", draft.repository().commitSha())
                .param("snapshot", draft.repository().snapshotId()).param("documents", documentsJson)
                .param("corrections", draft.correctionsJson()).param("hash", draft.inputHash())
                .query(UUID.class).single();
    }

    Optional<InputSetRow> inputSet(UUID id) {
        return jdbc.sql("""
                SELECT id, requirement_id, revision_no, repository_id, commit_sha, snapshot_id, documents::text,
                       corrections::text, input_hash
                FROM app.analysis_input_set WHERE id = :id
                """).param("id", id)
                .query((rs, n) -> new InputSetRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7),
                        rs.getString(8), rs.getString(9)))
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

    void insertItems(UUID tenantId, UUID versionId, UUID repositoryId, String commitSha, List<VerifiedItem> items) {
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
                        .param("repo", repo ? repositoryId : null).param("sha", repo ? commitSha : null)
                        .param("path", e.path()).param("start", e.startLine()).param("end", e.endLine())
                        .param("rangeHash", e.rangeSha256()).param("docVersion", e.documentVersionId())
                        .param("extraction", e.extractionNo()).param("chunk", e.chunkOrdinal())
                        .param("label", e.chunkLabel()).param("quote", e.quote()).param("support", e.supportPassed())
                        .update();
            }
        }
    }
}
