package io.github.tony079.sdlc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.RequirementFixtures;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.WorkerContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Milestone 3 end to end: the API (sdlc_app) submits, the transactional outbox carries RequirementSubmitted, and
 * the worker (sdlc_worker) routes the requirement (TDD T02/T03). Also covers the interim edit policy after
 * submission (decision Q-M3-5) and cancellation follow-up in the worker.
 */
class RequirementRoutingIT extends ApiIntegrationTest {

    private final JdbcTemplate admin = TestDatabase.get().admin();
    private OutboxDispatcher dispatcher;
    private ApiClient alice;
    private String project;
    private UUID acme;

    @BeforeEach
    void setUp() {
        dispatcher = WorkerContext.bean(OutboxDispatcher.class);
        alice = loggedIn("acme", "alice@acme.test");
        project = RequirementFixtures.project(alice);
        acme = TestDatabase.get().tenantId("acme");
    }

    private Response create(Map<String, Object> body) {
        Response created = alice.post("/api/v1/requirements", body);
        assertThat(created.status()).isEqualTo(201);
        return created;
    }

    private Response submit(Response detail) {
        Response submitted = alice.post("/api/v1/requirements/" + id(detail) + "/submit", null,
                RequirementFixtures.ifMatch(detail), true);
        assertThat(submitted.status()).isEqualTo(200);
        return submitted;
    }

    private static String id(Response detail) {
        return detail.body().path("id").asString();
    }

    private String state(String id) {
        return alice.get("/api/v1/requirements/" + id).body().path("state").asString();
    }

    private void drain() {
        while (dispatcher.dispatchNext(acme) != OutboxDispatcher.Outcome.NONE) {
            // deliver everything currently deliverable for this tenant
        }
    }

    private List<String> events(String id) {
        return admin.queryForList("""
                SELECT event_type || ':' || CASE WHEN published_at IS NOT NULL THEN 'published' ELSE 'pending' END
                FROM app.outbox_event WHERE aggregate_id = ?::uuid ORDER BY seq
                """, String.class, id);
    }

    @Test
    void existingRepositoryRequirementIsRoutedToRepositoryValidation() {
        Response submitted = submit(create(RequirementFixtures.complete(project)));
        String id = id(submitted);
        assertThat(submitted.body().path("state").asString()).isEqualTo("SUBMITTED");
        // Submission and event commit together; nothing is routed synchronously.
        assertThat(events(id)).containsExactly("RequirementSubmitted:pending");
        assertThat(admin.queryForObject("SELECT payload ->> 'route' FROM app.outbox_event WHERE aggregate_id = ?::uuid",
                String.class, id)).isEqualTo("REPOSITORY");

        drain();

        assertThat(state(id)).isEqualTo("REPO_VALIDATING");
        assertThat(events(id)).containsExactly("RequirementSubmitted:published", "RepoValidationStarted:published");
        Response timeline = alice.get("/api/v1/requirements/" + id + "/timeline");
        assertThat(timeline.body().get(2).path("transition").asString()).isEqualTo("T02_ROUTE_REPOSITORY");
        assertThat(timeline.body().get(2).path("actorId").isNull()).isTrue();
        assertThat(admin.queryForMap("""
                SELECT s.stage_key, s.status, j.job_type, j.status AS job_status, j.idempotency_key
                FROM app.stage_execution s JOIN app.job j ON j.stage_execution_id = s.id
                JOIN app.workflow_instance i ON i.id = s.instance_id WHERE i.requirement_id = ?::uuid
                """, id))
                .containsEntry("stage_key", "REPO_VALIDATION").containsEntry("status", "READY")
                .containsEntry("job_type", "REPO_VALIDATE").containsEntry("job_status", "QUEUED")
                .containsEntry("idempotency_key", "repo-validate:" + id + ":1");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE resource_id = ?::uuid AND action = 'REQUIREMENT_ROUTED'
                  AND actor_type = 'SYSTEM'
                """, Integer.class, id)).isEqualTo(1);
    }

    @Test
    void newProductRequirementIsRoutedToManualIntakeWithoutAnyJob() {
        Map<String, Object> body = RequirementFixtures.complete(project);
        body.put("kind", "NEW_PRODUCT");
        body.remove("repositoryUrl");
        String id = id(submit(create(body)));

        drain();

        assertThat(state(id)).isEqualTo("MANUAL_INTAKE");
        assertThat(events(id)).containsExactly("RequirementSubmitted:published", "ManualIntakeEntered:published");
        // PRD 0.4: no extraction, repository validation or BA job is scheduled in MANUAL_INTAKE.
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE aggregate_id = ?::uuid", Integer.class, id))
                .isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.stage_execution s JOIN app.workflow_instance i ON i.id = s.instance_id
                WHERE i.requirement_id = ?::uuid
                """, Integer.class, id)).isZero();
    }

    /** A duplicated event (at-least-once delivery) has no second business effect. */
    @Test
    void duplicateSubmissionEventDoesNotRouteTwice() {
        String id = id(submit(create(RequirementFixtures.complete(project))));
        admin.update("""
                INSERT INTO app.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                SELECT tenant_id, aggregate_type, aggregate_id, event_type, payload FROM app.outbox_event
                WHERE aggregate_id = ?::uuid
                """, id);

        drain();

        assertThat(state(id)).isEqualTo("REPO_VALIDATING");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.workflow_transition t JOIN app.workflow_instance i ON i.id = t.instance_id
                WHERE i.requirement_id = ?::uuid AND t.transition = 'T02_ROUTE_REPOSITORY'
                """, Integer.class, id)).isEqualTo(1);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE aggregate_id = ?::uuid", Integer.class, id))
                .isEqualTo(1);
        assertThat(events(id)).containsExactly("RequirementSubmitted:published", "RequirementSubmitted:published",
                "RepoValidationStarted:published");
    }

    /** Decision Q-M3-5: SUBMITTED stays editable without a kind change; routed states are not editable. */
    @Test
    void editingAfterSubmissionFollowsTheInterimPolicy() {
        Response submitted = submit(create(RequirementFixtures.complete(project)));
        String id = id(submitted);

        Map<String, Object> changedKind = RequirementFixtures.contentOf(submitted);
        changedKind.put("kind", "NEW_PRODUCT");
        changedKind.put("repositoryUrl", null);
        Response refused = alice.put("/api/v1/requirements/" + id, changedKind, RequirementFixtures.ifMatch(submitted));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("KIND_CHANGE_NOT_ALLOWED");

        Map<String, Object> sameKind = RequirementFixtures.contentOf(submitted);
        sameKind.put("description", "Clarified before routing.");
        Response edited = alice.put("/api/v1/requirements/" + id, sameKind, RequirementFixtures.ifMatch(submitted));
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().path("currentRevisionNo").asInt()).isEqualTo(2);

        drain();
        Response routed = alice.get("/api/v1/requirements/" + id);
        Response afterRouting = alice.put("/api/v1/requirements/" + id, sameKind, RequirementFixtures.ifMatch(routed));
        assertThat(afterRouting.status()).isEqualTo(409);
        assertThat(afterRouting.errorCode()).isEqualTo("REQUIREMENT_NOT_EDITABLE");
    }

    /** Owner cancellation from a routed state; the worker then cancels the queued job and its stage. */
    @Test
    void cancellationInRepositoryValidationCancelsTheQueuedJob() {
        String id = id(submit(create(RequirementFixtures.complete(project))));
        drain();
        Response routed = alice.get("/api/v1/requirements/" + id);
        Response cancelled = alice.post("/api/v1/requirements/" + id + "/cancel",
                Map.of("reason", "No longer needed by the business."), RequirementFixtures.ifMatch(routed), true);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCELLED");

        drain();

        assertThat(admin.queryForObject("SELECT status FROM app.job WHERE aggregate_id = ?::uuid", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(admin.queryForObject("""
                SELECT s.status FROM app.stage_execution s JOIN app.workflow_instance i ON i.id = s.instance_id
                WHERE i.requirement_id = ?::uuid
                """, String.class, id)).isEqualTo("CANCELLED");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.audit_event WHERE resource_id = ?::uuid AND action = 'JOBS_CANCELLED'
                """, Integer.class, id)).isEqualTo(1);
    }

    @Test
    void cancellationInManualIntakeByTheOwner() {
        Map<String, Object> body = RequirementFixtures.complete(project);
        body.put("kind", "NEW_PRODUCT");
        body.remove("repositoryUrl");
        String id = id(submit(create(body)));
        drain();
        Response intake = alice.get("/api/v1/requirements/" + id);
        assertThat(intake.body().path("state").asString()).isEqualTo("MANUAL_INTAKE");
        Response cancelled = alice.post("/api/v1/requirements/" + id + "/cancel",
                Map.of("reason", "Will be raised as a new request."), RequirementFixtures.ifMatch(intake), true);
        assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCELLED");
    }

    /** Cancelled before the worker routed it: the routing event is obsolete and changes nothing. */
    @Test
    void cancellationBeforeRoutingMakesTheRoutingEventObsolete() {
        Response submitted = submit(create(RequirementFixtures.complete(project)));
        String id = id(submitted);
        Response cancelled = alice.post("/api/v1/requirements/" + id + "/cancel",
                Map.of("reason", "Submitted by mistake today."), RequirementFixtures.ifMatch(submitted), true);
        assertThat(cancelled.status()).isEqualTo(200);

        drain();

        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThat(events(id)).containsExactly("RequirementSubmitted:published", "RequirementCancelled:published");
        assertThat(admin.queryForObject("SELECT count(*) FROM app.job WHERE aggregate_id = ?::uuid", Integer.class, id))
                .isZero();
    }

    /** The worker processes each tenant inside its own context: another tenant's events are untouched. */
    @Test
    void dispatchIsTenantScoped() {
        String id = id(submit(create(RequirementFixtures.complete(project))));
        UUID globex = TestDatabase.get().tenantId("globex");
        while (dispatcher.dispatchNext(globex) != OutboxDispatcher.Outcome.NONE) {
            // globex has its own events only
        }
        assertThat(events(id)).containsExactly("RequirementSubmitted:pending");
        drain();
        assertThat(state(id)).isEqualTo("REPO_VALIDATING");
    }
}
