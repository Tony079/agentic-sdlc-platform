package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.businessanalysis.internal.ReviewRepository.VersionRow;
import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.ClientUsers;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.sourcerepo.api.EnvironmentSources;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.workflow.api.Transition;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;
import io.github.tony079.sdlc.workflow.api.WorkflowState;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GD-23: what a requirement was analysed against (environment, repositories, branches, commits), whether those branches
 * have moved since, and the author's explicit re-analysis. Nothing is invalidated automatically when a branch moves.
 */
@Service
@ConditionalOnWebApplication
class SourcesService {

    record Source(UUID repositoryId, String repository, String branch, String analysedCommit, String currentCommit,
                  boolean moved) {
    }

    record Pending(String repository, String branch, String status, String failureCode) {
    }

    record View(UUID environmentId, String environmentName, boolean production, String state, boolean locked,
                boolean canChangeEnvironment, OffsetDateTime analysedAt, List<Source> analysed, List<Pending> preparing,
                boolean branchesMoved, boolean newRepositories, boolean canReanalyse, String reanalyseHint) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final WorkflowEngine workflow;
    private final ReviewRepository reviews;
    private final EnvironmentSources environments;
    private final AuditWriter audit;

    SourcesService(JdbcClient jdbc, PlatformTransactionManager transactionManager, WorkflowEngine workflow,
                   ReviewRepository reviews, EnvironmentSources environments, AuditWriter audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.workflow = workflow;
        this.reviews = reviews;
        this.environments = environments;
        this.audit = audit;
    }

    View view(UUID requirementId) {
        CurrentUser me = ClientUsers.require();
        return tx.execute(s -> load(requirementId, me));
    }

    /** The author re-analyses the released BRD against the environment's current branch heads (T27 then T28). */
    View reanalyse(UUID requirementId) {
        CurrentUser me = ClientUsers.require();
        tx.executeWithoutResult(s -> {
            View v = load(requirementId, me);
            if (!v.canReanalyse()) {
                throw ApiException.conflict("REANALYSIS_NOT_AVAILABLE", v.reanalyseHint() == null
                        ? "Re-analysis is not available for this requirement now." : v.reanalyseHint());
            }
            workflow.lockState(requirementId);
            VersionRow released = reviews.latestWithStatus(requirementId, Set.of("RELEASED"))
                    .orElseThrow(ApiException::notFound);
            environments.openRound(requirementId, v.environmentId());
            reviews.insertClientChange(TenantContext.requireTenant(), requirementId, released.id(),
                    "Re-analyse against the latest code of the " + v.environmentName()
                            + " environment: its branches have moved since this BRD was produced.", me.userId());
            workflow.transition(requirementId, Transition.T27_CLIENT_CHANGES, "Re-analysis against moved branches");
            audit.record(AuditAction.REQUIREMENT_REVISED, "REQUIREMENT", requirementId, AuditResult.SUCCESS);
        });
        return view(requirementId);
    }

    private View load(UUID requirementId, CurrentUser me) {
        record Req(UUID projectId, UUID ownerId, UUID environmentId) {
        }
        Req req = jdbc.sql("SELECT project_id, created_by, target_environment_id FROM app.requirement WHERE id = :id")
                .param("id", requirementId)
                .query((rs, n) -> new Req(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class))).optional().orElseThrow(ApiException::notFound);
        WorkflowState state = workflow.state(requirementId);
        boolean owner = me.userId().equals(req.ownerId());
        String envName = null;
        boolean production = false;
        if (req.environmentId() != null) {
            Object[] env = jdbc.sql("SELECT name, is_production FROM app.environment WHERE id = :e")
                    .param("e", req.environmentId()).query((rs, n) -> new Object[] {rs.getString(1), rs.getBoolean(2)})
                    .single();
            envName = (String) env[0];
            production = (Boolean) env[1];
        }
        boolean locked = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app.brd_quote WHERE requirement_id = :id AND status = 'ACCEPTED')
                    OR EXISTS (SELECT 1 FROM app.analysis_input_set WHERE requirement_id = :id)
                """).param("id", requirementId).query(Boolean.class).single();

        // The latest analysis: its sources (or its single legacy repository) and the environment's current heads.
        record Analysed(UUID repositoryId, String fullName, String branch, String commit, OffsetDateTime at) {
        }
        List<Analysed> analysed = jdbc.sql("""
                SELECT (src ->> 'repositoryId')::uuid, src ->> 'fullName', src ->> 'branch', src ->> 'commitSha', s.created_at
                FROM app.analysis_input_set s, jsonb_array_elements(s.sources) src
                WHERE s.id = (SELECT id FROM app.analysis_input_set WHERE requirement_id = :id ORDER BY created_at DESC LIMIT 1)
                """).param("id", requirementId)
                .query((rs, n) -> new Analysed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list();
        List<Source> sources = new ArrayList<>();
        boolean moved = false;
        for (Analysed a : analysed) {
            Object[] current = req.environmentId() == null ? null : jdbc.sql("""
                    SELECT branch, head_sha FROM app.environment_branch WHERE environment_id = :e AND repository_id = :r
                    """).param("e", req.environmentId()).param("r", a.repositoryId())
                    .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2)}).optional().orElse(null);
            String head = current == null ? null : (String) current[1];
            boolean m = current == null || !a.branch().equals(current[0]) || (head != null && !head.equals(a.commit()));
            moved |= m;
            sources.add(new Source(a.repositoryId(), a.fullName(), a.branch(), a.commit(), head, m));
        }
        boolean newRepos = !analysed.isEmpty() && req.environmentId() != null && jdbc.sql("""
                SELECT count(*) FROM app.environment_branch WHERE environment_id = :e AND status = 'VERIFIED'
                """).param("e", req.environmentId()).query(Integer.class).single() > analysed.size();

        // Sources of a newer round still being prepared (pinning, snapshot, index).
        List<Pending> preparing = jdbc.sql("""
                SELECT r.github_owner || '/' || r.github_name, rr.branch, rr.status, rr.failure_code,
                       EXISTS (SELECT 1 FROM app.repository_snapshot s WHERE s.requirement_repository_id = rr.id
                               AND s.status = 'INDEXED')
                FROM app.requirement_repository rr JOIN app.repository r ON r.id = rr.repository_id
                WHERE rr.requirement_id = :id AND rr.environment_id IS NOT NULL
                  AND rr.round = (SELECT max(round) FROM app.requirement_repository WHERE requirement_id = :id)
                ORDER BY 1
                """).param("id", requirementId)
                .query((rs, n) -> new Pending(rs.getString(1), rs.getString(2),
                        rs.getBoolean(5) ? "READY" : "FAILED".equals(rs.getString(3)) ? "FAILED" : "PREPARING",
                        rs.getString(4))).list();
        boolean stillPreparing = preparing.stream().anyMatch(p -> !"READY".equals(p.status()));

        String hint = null;
        boolean can = false;
        if (req.environmentId() == null || analysed.isEmpty()) {
            hint = null;
        } else if (!owner) {
            hint = "The requirement's owner can re-analyse it.";
        } else if (stillPreparing || state == WorkflowState.CLIENT_CHANGES_REQUESTED || state == WorkflowState.ANALYSING) {
            hint = "A re-analysis is already being prepared.";
        } else if (state != WorkflowState.SHARED_WITH_CLIENT) {
            hint = "Re-analysis is available once the BRD has been released to you. Until then, the Garuda reviewer "
                    + "can request a revision.";
        } else if (!moved && !newRepos) {
            hint = "The branches have not moved since this analysis.";
        } else {
            can = true;
        }
        return new View(req.environmentId(), envName, production, state.name(), locked,
                owner && !locked && state != WorkflowState.CANCELLED,
                analysed.isEmpty() ? null : analysed.get(0).at(), sources, stillPreparing ? preparing : List.of(),
                moved, newRepos, can, hint);
    }

}
