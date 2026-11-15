package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;
import io.github.tony079.sdlc.sourcerepo.api.EnvironmentSources;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
class EnvironmentSourceRounds implements EnvironmentSources {

    private final JdbcClient jdbc;
    private final OutboxPublisher outbox;

    EnvironmentSourceRounds(JdbcClient jdbc, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    public Optional<UUID> resolve(UUID projectId, UUID chosen) {
        if (chosen != null) {
            return jdbc.sql("SELECT id FROM app.environment WHERE id = :e AND project_id = :p").param("e", chosen)
                    .param("p", projectId).query(UUID.class).optional();
        }
        return jdbc.sql("SELECT id FROM app.environment WHERE project_id = :p AND is_production").param("p", projectId)
                .query(UUID.class).optional();
    }

    @Override
    public int openRound(UUID requirementId, UUID environmentId) {
        record Pair(UUID branchId, UUID repositoryId, String branch) {
        }
        List<Pair> pairs = jdbc.sql("""
                SELECT b.id, b.repository_id, b.branch FROM app.environment_branch b
                JOIN app.repository r ON r.id = b.repository_id
                WHERE b.environment_id = :e AND b.status = 'VERIFIED' AND r.status = 'VERIFIED'
                ORDER BY r.github_owner, r.github_name
                """).param("e", environmentId)
                .query((rs, n) -> new Pair(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .list();
        if (pairs.isEmpty()) {
            throw ApiException.validation("The environment has no verified repository branch yet. Ask the project's "
                    + "Environment Manager to add one.");
        }
        int round = jdbc.sql("SELECT coalesce(max(round), 0) + 1 FROM app.requirement_repository WHERE requirement_id = :r")
                .param("r", requirementId).query(Integer.class).single();
        UUID tenant = TenantContext.requireTenant();
        for (Pair p : pairs) {
            jdbc.sql("""
                    INSERT INTO app.requirement_repository (tenant_id, requirement_id, repository_id, environment_id,
                                                            environment_branch_id, branch, round)
                    VALUES (:t, :r, :repo, :e, :b, :branch, :round)
                    """).param("t", tenant).param("r", requirementId).param("repo", p.repositoryId())
                    .param("e", environmentId).param("b", p.branchId()).param("branch", p.branch()).param("round", round)
                    .update();
        }
        if (round > 1) {
            // The worker pins the new round (ENV_PIN); the API never enqueues jobs itself.
            outbox.publish("REQUIREMENT", requirementId, EventTypes.SOURCES_ROUND_OPENED,
                    Map.of("requirementId", requirementId.toString(), "round", round));
        }
        return pairs.size();
    }

    @Override
    public boolean hasSources(UUID requirementId) {
        return Boolean.TRUE.equals(jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM app.requirement_repository WHERE requirement_id = :r)")
                .param("r", requirementId).query(Boolean.class).single());
    }
}
