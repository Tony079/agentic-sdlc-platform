package io.github.tony079.sdlc.businessanalysis.internal;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.SdlcPlatformApplication;
import io.github.tony079.sdlc.identity.api.StaffCapability;
import io.github.tony079.sdlc.identity.internal.StaffFixtures;
import io.github.tony079.sdlc.identity.internal.StaffFixtures.Enrolled;
import io.github.tony079.sdlc.modelgateway.internal.DevelopmentMockModelProvider;
import io.github.tony079.sdlc.outbox.internal.OutboxDispatcher;
import io.github.tony079.sdlc.queue.internal.JdbcJobStore;
import io.github.tony079.sdlc.queue.internal.JobWorker;
import io.github.tony079.sdlc.support.AnalysisScenario;
import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.FakeClamd;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.support.TestStorage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;

/**
 * The working prototype end to end, with the two development adapters and nothing else replaced: the real API, the
 * real worker (its own context, configured as the prototype Compose stack is), PostgreSQL with RLS, the job queue and
 * outbox, the scanner protocol, sandboxed extraction, the evidence verifier and every approval gate.
 *
 * <p>Tenant "globex" is used so that this worker never touches jobs of the tests that run on the fake GitHub.
 */
class PrototypeWorkflowIT extends ApiIntegrationTest {

    private static final String TENANT = "globex";
    private static ConfigurableApplicationContext prototypeWorker;

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();
    private final UUID tenant = db.tenantId(TENANT);

    @BeforeAll
    static void startPrototypeWorker() {
        TestDatabase db = TestDatabase.get();
        prototypeWorker = new SpringApplicationBuilder(SdlcPlatformApplication.class)
                .profiles("worker")
                .run("--spring.datasource.url=" + db.appUrl(),
                        "--spring.datasource.username=" + db.workerUser(),
                        "--spring.datasource.password=" + db.workerPassword(),
                        "--sdlc.worker.scheduling=false",
                        "--sdlc.worker.outbox.backoff-base=0s",
                        "--sdlc.storage.root=" + TestStorage.ROOT,
                        "--sdlc.storage.encryption-key=" + TestStorage.KEY,
                        "--sdlc.scanner.clamd.port=" + FakeClamd.port(),
                        "--sdlc.scanner.clamd.timeout=5s",
                        "--sdlc.documents.extraction.enabled=true",
                        "--sdlc.documents.extraction.timeout=60s",
                        "--sdlc.documents.extraction.sandbox=unshare-net",
                        "--sdlc.documents.extraction.sandbox-required=true",
                        // The prototype configuration: both development adapters, GitHub off, no provider key.
                        "--sdlc.model.provider=" + DevelopmentMockModelProvider.NAME,
                        "--sdlc.model.model-id=" + DevelopmentMockModelProvider.MODEL_ID,
                        "--sdlc.repository.source=development",
                        "--sdlc.github.enabled=false",
                        "--logging.structured.format.console=");
    }

    @AfterAll
    static void stopPrototypeWorker() {
        if (prototypeWorker != null) {
            prototypeWorker.close();
        }
    }

    private void drive() {
        OutboxDispatcher dispatcher = prototypeWorker.getBean(OutboxDispatcher.class);
        JobWorker worker = prototypeWorker.getBean(JobWorker.class);
        for (int round = 0; round < 40; round++) {
            boolean work = false;
            while (dispatcher.dispatchNext(tenant) != OutboxDispatcher.Outcome.NONE) {
                work = true;
            }
            for (JdbcJobStore.Claim claim : worker.claim(tenant, 20)) {
                worker.process(claim);
                work = true;
            }
            if (!work) {
                return;
            }
        }
    }

    private String state(String requirementId) {
        return admin.queryForObject("SELECT projected_state FROM app.workflow_instance WHERE requirement_id = ?::uuid",
                String.class, requirementId);
    }

    private ApiClient staff(StaffCapability capability) {
        Enrolled member = StaffFixtures.createEnrolled(StaffFixtures.uniqueEmail("proto"), AnalysisScenario.PASSWORD);
        StaffFixtures.assign(member.email(), TENANT);
        StaffFixtures.grant(member.email(), capability);
        return AnalysisScenario.staffSession(client(), member, TENANT, 0);
    }

    @Test
    void aRequirementGoesFromCreationToAnApprovedAndReleasedBrdOnTheDevelopmentAdapters() {
        ApiClient gina = loggedIn(TENANT, "gina@globex.test");
        ApiClient gary = loggedIn(TENANT, "gary@globex.test");

        // 1-2. Project, requirement, repository authorisation (C-18) and staff registration (C-01).
        String repositoryUrl = "https://github.com/sdlc-dev/sample-orders-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = complete(project(gina));
        body.put("title", "Record a reason for refunds");
        body.put("description", "Support staff must record a reason when they refund or cancel an order.");
        body.put("acceptanceCriteria", "A refund without a reason is rejected.");
        body.put("repositoryUrl", repositoryUrl);
        Response created = gina.post("/api/v1/requirements", body);
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asString();
        String projectId = created.body().path("projectId").asString();
        String token = gina.post("/api/v1/requirements/" + id + "/repository-link-requests", Map.of())
                .body().path("token").asString();
        Response redeemed = gary.post("/api/v1/repository-link-requests/redeem",
                Map.of("token", token, "repositoryUrl", repositoryUrl));
        assertThat(redeemed.status()).as(redeemed.body().toString()).isEqualTo(201);
        assertThat(staff(StaffCapability.REPOSITORY_REGISTER).post("/api/v1/projects/" + projectId + "/repositories",
                Map.of("linkId", redeemed.body().path("linkId").asString())).status()).isEqualTo(202);

        // 3. Document upload: validated, scanned, stored.
        Response uploaded = gina.upload("/api/v1/requirements/" + id + "/documents", "refund-policy.txt", "text/plain",
                "Refund policy\n\nEvery refund must carry a reason chosen by support staff.\n"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(uploaded.status()).as(uploaded.body().toString()).isEqualTo(201);

        // 2. Submission; the worker verifies, validates, snapshots, indexes, scans, extracts and analyses.
        Response latest = gina.get("/api/v1/requirements/" + id);
        assertThat(gina.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(latest), true).status())
                .isEqualTo(200);
        drive();

        // 6. The mock's blocking question is a persisted clarification; the analysis waits for the owner.
        assertThat(state(id)).isEqualTo("AWAITING_CLARIFICATION");
        JsonNode inbox = gina.get("/api/v1/clarifications").body();
        JsonNode clarification = inbox.valueStream().filter(c -> c.path("requirementId").asString().equals(id))
                .findFirst().orElseThrow();
        assertThat(clarification.path("question").asString()).startsWith(DevelopmentMockModelProvider.MARKER);
        assertThat(admin.queryForObject("SELECT count(*) FROM app.brd_version WHERE requirement_id = ?::uuid",
                Integer.class, id)).isZero();
        assertThat(gina.post("/api/v1/clarifications/" + clarification.path("id").asString() + "/answer",
                Map.of("text", "Support staff only.")).status()).isEqualTo(200);
        drive();

        // 5 and 7. A persisted BRD version, labelled as mock output, with citations the verifier accepted.
        assertThat(state(id)).isEqualTo("INTERNAL_REVIEW");
        Map<String, Object> version = admin.queryForMap("""
                SELECT id::text AS id, content_hash, model_provider, model_id FROM app.brd_version
                WHERE requirement_id = ?::uuid AND status = 'IN_REVIEW'
                """, id);
        assertThat(version).containsEntry("model_provider", "development-mock")
                .containsEntry("model_id", "development-mock-1");
        String versionId = (String) version.get("id");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.brd_item WHERE brd_version_id = ?::uuid AND statement NOT LIKE '[DEVELOPMENT MOCK]%'
                """, Integer.class, versionId)).isZero();
        List<String> verified = admin.queryForList("""
                SELECT e.path FROM app.brd_item_evidence e JOIN app.brd_item i ON i.id = e.brd_item_id
                WHERE i.brd_version_id = ?::uuid AND e.type = 'REPOSITORY' AND i.kind = 'FACT'
                  AND i.support_state = 'VERIFIED'
                """, String.class, versionId);
        assertThat(verified).isNotEmpty().allMatch(p -> p.startsWith("src/main/java/com/example/orders/"));
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.brd_item_evidence e JOIN app.brd_item i ON i.id = e.brd_item_id
                WHERE i.brd_version_id = ?::uuid AND e.type = 'DOCUMENT' AND i.support_state = 'VERIFIED'
                """, Integer.class, versionId)).isEqualTo(1);
        assertThat(admin.queryForObject("""
                SELECT usage_source FROM app.model_call_record WHERE requirement_id = ?::uuid ORDER BY created_at LIMIT 1
                """, String.class, id)).isEqualTo("ESTIMATED");

        // 8. Review: any flagged item acknowledged, then approval bound to the reviewed content. No gate is bypassed.
        ApiClient reviewer = staff(StaffCapability.BUSINESS_REVIEWER);
        Response detail = reviewer.get("/api/v1/staff/brd-versions/" + versionId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().path("version").path("modelProvider").asString()).isEqualTo("development-mock");
        Map<String, Object> approval = Map.of("expectedContentHash", version.get("content_hash"));
        // Approval version binding: a hash other than the reviewed content is refused.
        assertThat(reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/approve",
                Map.of("expectedContentHash", "0".repeat(64))).errorCode()).isEqualTo("VERSION_CHANGED");
        for (String key : admin.queryForList("""
                SELECT item_key FROM app.brd_item WHERE brd_version_id = ?::uuid AND support_state = 'FLAGGED'
                """, String.class, versionId)) {
            assertThat(reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/flags/" + key + "/acknowledge",
                    Map.of("note", "Mock output acknowledged for the prototype.")).status()).isEqualTo(200);
        }
        Response approved = reviewer.post("/api/v1/staff/brd-versions/" + versionId + "/approve", approval);
        assertThat(approved.status()).as(approved.body().toString()).isEqualTo(200);
        assertThat(state(id)).isEqualTo("INTERNAL_APPROVED");
        // The client still sees nothing (AC-CORE-08); release is a separate person's decision (O-16).
        assertThat(gina.get("/api/v1/requirements/" + id + "/brd").status()).isEqualTo(404);
        ApiClient manager = staff(StaffCapability.RELEASE_APPROVE);
        JsonNode pending = manager.get("/api/v1/staff/releases").body().valueStream()
                .filter(n -> n.path("brdVersionId").asString().equals(versionId)).findFirst().orElseThrow();
        Response released = manager.post("/api/v1/staff/brd-versions/" + versionId + "/release", Map.of(
                "expectedContentHash", pending.path("contentHash").asString(),
                "expectedCommitSha", pending.path("commitSha").asString(),
                "reason", "Prototype walkthrough: gates passed."));
        assertThat(released.status()).as(released.body().toString()).isEqualTo(200);
        drive();
        assertThat(state(id)).isEqualTo("SHARED_WITH_CLIENT");
        Response clientBrd = gina.get("/api/v1/requirements/" + id + "/brd");
        assertThat(clientBrd.status()).isEqualTo(200);
        assertThat(clientBrd.body().path("version").path("modelProvider").asString()).isEqualTo("development-mock");

        // 9. Workflow status tracking.
        assertThat(gina.get("/api/v1/requirements/" + id + "/timeline").body().valueStream()
                .map(t -> t.path("toState").asString())).containsSubsequence("SUBMITTED", "REPO_VALIDATING",
                "ANALYSING", "AWAITING_CLARIFICATION", "ANALYSING", "INTERNAL_REVIEW", "INTERNAL_APPROVED",
                "SHARED_WITH_CLIENT");

        // 10. Audit history: content-free, the owner's own actions marked, other users and tenants refused.
        Response audit = gina.get("/api/v1/requirements/" + id + "/audit");
        assertThat(audit.status()).isEqualTo(200);
        assertThat(audit.body().valueStream().map(a -> a.path("action").asString())).contains("REQUIREMENT_CREATED",
                "REQUIREMENT_SUBMITTED", "DOCUMENT_UPLOADED", "BRD_APPROVED");
        assertThat(audit.body().valueStream().filter(a -> a.path("action").asString().equals("REQUIREMENT_CREATED"))
                .findFirst().orElseThrow().path("actor").asString()).isEqualTo("YOU");
        assertThat(audit.body().valueStream().map(a -> a.path("actor").asString())).contains("STAFF", "SYSTEM");
        assertThat(audit.body().toString()).doesNotContain("Support staff only").doesNotContain("refund-policy");
        assertThat(gary.get("/api/v1/requirements/" + id + "/audit").status()).isEqualTo(404);
        assertThat(loggedIn("acme", "alice@acme.test").get("/api/v1/requirements/" + id + "/audit").status())
                .isEqualTo(404);
    }

    @Test
    void aRepositoryOutsideTheSampleOwnerIsRefusedLikeAnUninstalledApp() {
        ApiClient gina = loggedIn(TENANT, "gina@globex.test");
        ApiClient gary = loggedIn(TENANT, "gary@globex.test");
        String repositoryUrl = "https://github.com/real-org/service-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = complete(project(gina));
        body.put("repositoryUrl", repositoryUrl);
        Response created = gina.post("/api/v1/requirements", body);
        String id = created.body().path("id").asString();
        String token = gina.post("/api/v1/requirements/" + id + "/repository-link-requests", Map.of())
                .body().path("token").asString();
        Response redeemed = gary.post("/api/v1/repository-link-requests/redeem",
                Map.of("token", token, "repositoryUrl", repositoryUrl));
        staff(StaffCapability.REPOSITORY_REGISTER).post("/api/v1/projects/" + created.body().path("projectId").asString()
                + "/repositories", Map.of("linkId", redeemed.body().path("linkId").asString()));
        gina.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(gina.get("/api/v1/requirements/" + id)), true);
        drive();

        assertThat(admin.queryForObject("""
                SELECT r.status FROM app.repository r JOIN app.requirement_repository rr ON rr.repository_id = r.id
                WHERE rr.requirement_id = ?::uuid
                """, String.class, id)).isEqualTo("ACCESS_DENIED");
        assertThat(state(id)).isEqualTo("FAILED");
    }

    @Test
    void theRuntimeEndpointNamesTheConfiguredAdapters() {
        // The shared API context runs with the defaults: no provider, GitHub source, so nothing is flagged as mock.
        Response runtime = loggedIn(TENANT, "gina@globex.test").get("/api/v1/runtime");
        assertThat(runtime.status()).isEqualTo(200);
        assertThat(runtime.body().path("modelProvider").asString()).isEqualTo("none");
        assertThat(runtime.body().path("repositorySource").asString()).isEqualTo("github");
        assertThat(runtime.body().path("developmentAdapters")).isEmpty();
        assertThat(client().get("/api/v1/runtime").status()).isEqualTo(401);
    }
}
