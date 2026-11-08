package io.github.tony079.sdlc.workflow.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.audit.api.AuditWriter;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditAction;
import io.github.tony079.sdlc.audit.api.AuditWriter.AuditResult;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.github.tony079.sdlc.tenancy.api.TenantLookup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records requirement closure (owner decision O-25, 2026-11-08). A requirement is closed only when:
 * <ol>
 *   <li>it is in a terminal state (CANCELLED or REJECTED; Release 1 has no completion state, so a released BRD does not
 *       close a requirement),</li>
 *   <li>no job of the requirement or of its documents is queued or running, no stage execution is unfinished, no
 *       clarification is open or answered-but-unresolved, and no BRD version is in review or internally approved
 *       (the cancellation follow-up withdraws them first).</li>
 * </ol>
 * The closure row is written once by the worker, with the time and the actor of the terminal transition, and can never
 * be updated or deleted (no grant). Retention counts from it. Runs in the worker only.
 */
@Component
@ConditionalOnProperty(prefix = "sdlc.worker", name = "enabled", havingValue = "true")
public class ClosureRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClosureRecorder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantLookup tenants;
    private final AuditWriter audit;

    ClosureRecorder(JdbcClient jdbc, PlatformTransactionManager transactionManager, TenantLookup tenants,
                    AuditWriter audit) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tenants = tenants;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${sdlc.worker.closure-interval:60s}", initialDelayString = "20s")
    void scheduled() {
        try {
            recordEligible();
        } catch (RuntimeException e) {
            log.error("Closure recording failed", e);
        }
    }

    /** Records every eligible closure in every tenant; returns how many were recorded. */
    public int recordEligible() {
        int recorded = 0;
        for (UUID tenant : tx.execute(s -> tenants.activeTenantIds())) {
            recorded += TenantContext.callAs(tenant, () -> tx.execute(s -> recordInTenant(tenant)));
        }
        return recorded;
    }

    private int recordInTenant(UUID tenant) {
        List<UUID> eligible = jdbc.sql("""
                SELECT i.requirement_id FROM app.workflow_instance i
                WHERE i.projected_state IN ('CANCELLED', 'REJECTED')
                  AND NOT EXISTS (SELECT 1 FROM app.requirement_closure c WHERE c.requirement_id = i.requirement_id)
                  AND NOT EXISTS (SELECT 1 FROM app.job j WHERE j.status IN ('QUEUED', 'LEASED')
                                  AND (j.aggregate_id = i.requirement_id
                                       OR j.aggregate_id IN (SELECT d.id FROM app.document d
                                                             WHERE d.requirement_id = i.requirement_id)))
                  AND NOT EXISTS (SELECT 1 FROM app.stage_execution s WHERE s.instance_id = i.id
                                  AND s.status IN ('PENDING', 'BLOCKED', 'READY', 'RUNNING', 'WAITING_HUMAN'))
                  AND NOT EXISTS (SELECT 1 FROM app.clarification q WHERE q.requirement_id = i.requirement_id
                                  AND q.status IN ('OPEN', 'NEEDS_INFO', 'ANSWERED'))
                  AND NOT EXISTS (SELECT 1 FROM app.brd_version v WHERE v.requirement_id = i.requirement_id
                                  AND v.status IN ('IN_REVIEW', 'APPROVED_INTERNAL'))
                FOR UPDATE OF i SKIP LOCKED
                """).query(UUID.class).list();
        for (UUID requirement : eligible) {
            jdbc.sql("""
                    INSERT INTO app.requirement_closure (tenant_id, requirement_id, terminal_state, transition, terminal_at,
                                                         closed_by_type, closed_by_id)
                    SELECT :tenant, i.requirement_id, t.to_state, t.transition, t.occurred_at, t.actor_type, t.actor_id
                    FROM app.workflow_instance i JOIN app.workflow_transition t ON t.instance_id = i.id AND t.seq = i.last_seq
                    WHERE i.requirement_id = :requirement
                    ON CONFLICT (requirement_id) DO NOTHING
                    """).param("tenant", tenant).param("requirement", requirement).update();
            audit.record(AuditAction.REQUIREMENT_CLOSED, "REQUIREMENT", requirement, AuditResult.SUCCESS);
        }
        return eligible.size();
    }
}
