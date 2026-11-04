package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import io.github.tony079.sdlc.common.tx.TenantAwareTransactionManager;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database itself keeps workflow_instance.projected_state consistent with the append-only transition
 * history, as the runtime role sdlc_app (no privileged functions, no triggers). These are the guarantees the
 * workflow engine relies on; the engine's own rules are tested separately.
 */
class WorkflowConsistencyIT {

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static UUID tenant;
    private static UUID user;
    private static UUID project;

    private UUID instance;

    @BeforeAll
    static void setUp() {
        TestDatabase db = TestDatabase.get();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(db.appUrl());
        dataSource.setUsername(db.appUser());
        dataSource.setPassword(db.appPassword());
        dataSource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new TenantAwareTransactionManager(dataSource));
        tenant = db.tenantId("acme");
        user = db.admin().queryForObject("SELECT id FROM app.app_user WHERE email = 'alice@acme.test'", UUID.class);
        project = inTenant(() -> jdbc.queryForObject(
                "INSERT INTO app.project (tenant_id, name, created_by) VALUES (?, ?, ?) RETURNING id",
                UUID.class, tenant, "wf-consistency-" + UUID.randomUUID(), user));
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    /** A requirement in DRAFT: requirement, revision 1, instance and the CREATE transition in one transaction. */
    @BeforeEach
    void draftRequirement() {
        instance = inTenant(() -> {
            UUID requirement = jdbc.queryForObject("""
                    INSERT INTO app.requirement (tenant_id, project_id, key, created_by, current_revision_no)
                    VALUES (?, ?, ?, ?, 1) RETURNING id
                    """, UUID.class, tenant, project, "REQ-9" + (100000 + (int) (Math.random() * 899999)), user);
            jdbc.update("""
                    INSERT INTO app.requirement_revision (tenant_id, requirement_id, revision_no, kind, title, content_hash, created_by)
                    VALUES (?, ?, 1, 'NEW_PRODUCT', 'Consistency test', repeat('a', 64), ?)
                    """, tenant, requirement, user);
            UUID id = jdbc.queryForObject("""
                    INSERT INTO app.workflow_instance (tenant_id, requirement_id, definition_version, projected_state, last_seq)
                    VALUES (?, ?, 'R1-v1', 'DRAFT', 1) RETURNING id
                    """, UUID.class, tenant, requirement);
            jdbc.update("""
                    INSERT INTO app.workflow_transition (tenant_id, instance_id, seq, to_state, transition, actor_type, actor_id)
                    VALUES (?, ?, 1, 'DRAFT', 'CREATE', 'USER', ?)
                    """, tenant, id, user);
            return id;
        });
    }

    @Test
    void legalTransitionWithMatchingHistoryCommits() {
        move(2, "DRAFT", "SUBMITTED", "T01_SUBMIT");
        move(3, "SUBMITTED", "CANCELLED", "T29_CANCEL");
        List<String> history = inTenant(() -> jdbc.queryForList(
                "SELECT coalesce(from_state, '-') || '>' || to_state FROM app.workflow_transition WHERE instance_id = ? ORDER BY seq",
                String.class, instance));
        assertThat(history).containsExactly("->DRAFT", "DRAFT>SUBMITTED", "SUBMITTED>CANCELLED");
        assertThat(state()).isEqualTo("CANCELLED");
    }

    @Test
    void stateCannotChangeWithoutATransitionRow() {
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE app.workflow_instance SET projected_state = 'SUBMITTED', last_seq = 2 WHERE id = ?", instance)))
                .rootCause().hasMessageContaining("workflow_instance_matches_latest_transition");
        assertThat(state()).isEqualTo("DRAFT");
    }

    @Test
    void transitionMustStartFromTheActualCurrentState() {
        assertThatThrownBy(() -> moveRaw(2, "SUBMITTED", "CANCELLED", "T29_CANCEL"))
                .rootCause().hasMessageContaining("violates foreign key constraint");
        assertThat(state()).isEqualTo("DRAFT");
    }

    @Test
    void illegalStatePairIsRefused() {
        move(2, "DRAFT", "SUBMITTED", "T01_SUBMIT");
        assertThatThrownBy(() -> moveRaw(3, "SUBMITTED", "DRAFT", "T01_SUBMIT"))
                .rootCause().hasMessageContaining("violates check constraint");
    }

    @Test
    void cancelledIsTerminal() {
        move(2, "DRAFT", "CANCELLED", "T29_CANCEL");
        assertThatThrownBy(() -> moveRaw(3, "CANCELLED", "SUBMITTED", "T01_SUBMIT"))
                .rootCause().hasMessageContaining("violates check constraint");
        assertThat(state()).isEqualTo("CANCELLED");
    }

    /** Milestone 3: routing (T02/T03) is an engine transition; the database refuses it with a user actor. */
    @Test
    void routingTransitionsAreRefusedForAUserActor() {
        move(2, "DRAFT", "SUBMITTED", "T01_SUBMIT");
        assertThatThrownBy(() -> moveRaw(3, "SUBMITTED", "REPO_VALIDATING", "T02_ROUTE_REPOSITORY"))
                .rootCause().hasMessageContaining("workflow_transition_engine_actor_check");
        assertThatThrownBy(() -> moveRaw(3, "SUBMITTED", "MANUAL_INTAKE", "T03_ROUTE_MANUAL_INTAKE"))
                .rootCause().hasMessageContaining("workflow_transition_engine_actor_check");
        assertThatThrownBy(() -> moveRaw(3, "SUBMITTED", "MANUAL_INTAKE", "T02_ROUTE_REPOSITORY"))
                .rootCause().hasMessageContaining("violates check constraint");
        assertThat(state()).isEqualTo("SUBMITTED");
    }

    @Test
    void historyCannotBranch() {
        assertThatThrownBy(() -> inTenant(() -> {
            insertTransition(2, "DRAFT", "SUBMITTED", "T01_SUBMIT");
            insertTransition(2, "DRAFT", "CANCELLED", "T29_CANCEL");
            return null;
        })).rootCause().hasMessageContaining("duplicate key");
    }

    @Test
    void historyCannotBeRewrittenOrDeleted() {
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE app.workflow_transition SET to_state = 'CANCELLED' WHERE instance_id = ?", instance)))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "DELETE FROM app.workflow_transition WHERE instance_id = ?", instance)))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE app.requirement_revision SET title = 'rewritten'")))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void currentRevisionPointerMustNameAnExistingRevision() {
        UUID requirement = inTenant(() -> jdbc.queryForObject(
                "SELECT requirement_id FROM app.workflow_instance WHERE id = ?", UUID.class, instance));
        assertThatThrownBy(() -> inTenant(() -> jdbc.update(
                "UPDATE app.requirement SET current_revision_no = 7 WHERE id = ?", requirement)))
                .rootCause().hasMessageContaining("requirement_current_revision_fk");
    }

    private void move(int seq, String from, String to, String transition) {
        moveRaw(seq, from, to, transition);
    }

    private void moveRaw(int seq, String from, String to, String transition) {
        inTenant(() -> {
            jdbc.update("UPDATE app.workflow_instance SET projected_state = ?, last_seq = ?, version = version + 1 WHERE id = ?",
                    to, seq, instance);
            insertTransition(seq, from, to, transition);
            return null;
        });
    }

    private void insertTransition(int seq, String from, String to, String transition) {
        jdbc.update("""
                INSERT INTO app.workflow_transition (tenant_id, instance_id, seq, prev_seq, from_state, to_state,
                                                     transition, actor_type, actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'USER', ?)
                """, tenant, instance, seq, seq - 1, from, to, transition, user);
    }

    private String state() {
        return inTenant(() -> jdbc.queryForObject(
                "SELECT projected_state FROM app.workflow_instance WHERE id = ?", String.class, instance));
    }

    private static <T> T inTenant(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> tx.execute(status -> work.get()));
    }
}
