package io.github.tony079.sdlc.sourcerepo.internal;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** C-18 (Option 1): one-time link requests; the approver attaches a repository without seeing the requirement. */
class LinkRequestIT extends ApiIntegrationTest {

    private static final String URL = "https://github.com/acme-bank/loan-service";

    private final TestDatabase db = TestDatabase.get();
    private final JdbcTemplate admin = db.admin();

    private record Issued(ApiClient owner, String requirementId, Response created, String token, String requestId) {
    }

    private Issued issued() {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();
        Response request = alice.post("/api/v1/requirements/" + id + "/repository-link-requests", Map.of());
        assertThat(request.status()).as(request.body().toString()).isEqualTo(201);
        return new Issued(alice, id, created, request.body().path("token").asString(),
                request.body().path("requestId").asString());
    }

    private Response redeem(ApiClient client, String token, String url) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("repositoryUrl", url);
        return client.post("/api/v1/repository-link-requests/redeem", body);
    }

    private int rejections() {
        return admin.queryForObject("SELECT count(*) FROM app.platform_security_event WHERE type = 'LINK_REQUEST_REJECTED'",
                Integer.class);
    }

    @Test
    void ownerIssuesApproverRedeemsAndLearnsNothingAboutTheRequirement() {
        Issued issued = issued();
        assertThat(issued.token()).matches("^rlr_[A-Za-z0-9_-]{43}$");
        // Only the hash is stored.
        String stored = admin.queryForObject("SELECT token_hash FROM app.repository_link_request WHERE id = ?::uuid",
                String.class, issued.requestId());
        assertThat(stored).isEqualTo(LinkRequestService.sha256(issued.token())).doesNotContain(issued.token());

        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        Response redeemed = redeem(aaron, issued.token(), URL);
        assertThat(redeemed.status()).as(redeemed.body().toString()).isEqualTo(201);
        assertThat(redeemed.body().propertyNames()).containsExactlyInAnyOrder("linkId", "status");
        assertThat(redeemed.body().path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(redeemed.body().toString()).doesNotContain(issued.requirementId())
                .doesNotContain(issued.created().body().path("key").asString())
                .doesNotContain(issued.created().body().path("content").path("title").asString());

        // The approver still cannot see the requirement in any way.
        assertThat(aaron.get("/api/v1/requirements/" + issued.requirementId()).status()).isEqualTo(404);
        assertThat(aaron.get("/api/v1/requirements/" + issued.requirementId() + "/repository-link").status())
                .isEqualTo(404);
        assertThat(aaron.get("/api/v1/requirements").body().path("items").valueStream()
                .map(n -> n.path("id").asString())).doesNotContain(issued.requirementId());

        // The owner sees the authorised link.
        Response status = issued.owner().get("/api/v1/requirements/" + issued.requirementId() + "/repository-link");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().path("link").path("repositoryUrl").asString()).isEqualTo(URL);
        assertThat(status.body().path("link").path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(status.body().path("pendingRequest").isNull()).isTrue();

        // Audit: content-free, actor is the approver.
        UUID aaronId = admin.queryForObject("SELECT id FROM app.app_user WHERE email = 'aaron@acme.test'", UUID.class);
        assertThat(admin.queryForMap("""
                SELECT actor_id, actor_type, resource_type FROM app.audit_event
                WHERE action = 'REPOSITORY_LINK_ATTACHED' AND resource_id = ?::uuid
                """, redeemed.body().path("linkId").asString()))
                .containsEntry("actor_id", aaronId).containsEntry("actor_type", "USER")
                .containsEntry("resource_type", "REPOSITORY_LINK");

        // Single use.
        int before = rejections();
        Response again = redeem(aaron, issued.token(), URL);
        assertThat(again.status()).isEqualTo(404);
        assertThat(rejections()).isEqualTo(before + 1);
    }

    @Test
    void everyTokenFailureIsTheSameNotFound() throws Exception {
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        ApiClient gary = loggedIn("globex", "gary@globex.test");
        List<Response> failures = new ArrayList<>();

        // Unknown and malformed.
        failures.add(redeem(aaron, LinkRequestService.newToken(), URL));
        failures.add(redeem(aaron, "rlr_short", URL));
        // Wrong role: the owner (a Client User) presenting their own token.
        Issued own = issued();
        failures.add(redeem(own.owner(), own.token(), URL));
        // Other tenant: a Globex approver presenting an Acme token.
        failures.add(redeem(gary, own.token(), URL));
        // Revoked.
        Issued revoked = issued();
        assertThat(revoked.owner().delete("/api/v1/repository-link-requests/" + revoked.requestId()).status())
                .isEqualTo(204);
        failures.add(redeem(aaron, revoked.token(), URL));
        // Expired (moved into the past by the superuser, keeping the 1-168 hour rule).
        Issued expired = issued();
        admin.update("""
                UPDATE app.repository_link_request
                SET created_at = created_at - interval '80 hours', expires_at = expires_at - interval '80 hours'
                WHERE id = ?::uuid
                """, expired.requestId());
        failures.add(redeem(aaron, expired.token(), URL));
        // Requirement no longer linkable (cancelled).
        Issued cancelled = issued();
        assertThat(cancelled.owner().post("/api/v1/requirements/" + cancelled.requirementId() + "/cancel",
                Map.of("reason", "No longer needed by the business"), ifMatch(cancelled.created()), true).status())
                .isEqualTo(200);
        failures.add(redeem(aaron, cancelled.token(), URL));

        for (Response failure : failures) {
            assertThat(failure.status()).isEqualTo(404);
            assertThat(failure.errorCode()).isEqualTo("NOT_FOUND");
            assertThat(failure.body().path("error").path("message").asString())
                    .isEqualTo(failures.get(0).body().path("error").path("message").asString());
        }
        // Still usable by the right approver: the failed attempts consumed nothing.
        assertThat(redeem(aaron, own.token(), URL).status()).isEqualTo(201);
        // No token is stored or recorded anywhere in plaintext.
        for (String token : List.of(own.token(), revoked.token(), expired.token())) {
            assertThat(admin.queryForObject("""
                    SELECT count(*) FROM app.platform_security_event WHERE actor_ref LIKE ? OR target_ref LIKE ?
                    """, Integer.class, "%" + token + "%", "%" + token + "%")).isZero();
        }
    }

    @Test
    void onlyTheOwnerMayIssueViewOrRevoke() {
        Issued issued = issued();
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        ApiClient gary = loggedIn("globex", "gary@globex.test");
        for (ApiClient other : List.of(aaron, gary)) {
            assertThat(other.post("/api/v1/requirements/" + issued.requirementId() + "/repository-link-requests",
                    Map.of()).status()).isEqualTo(404);
            assertThat(other.delete("/api/v1/repository-link-requests/" + issued.requestId()).status()).isEqualTo(404);
        }
        assertThat(issued.owner().post("/api/v1/requirements/" + issued.requirementId() + "/repository-link-requests",
                Map.of("expiresInHours", 169)).status()).isEqualTo(422);
        assertThat(issued.owner().post("/api/v1/requirements/" + issued.requirementId() + "/repository-link-requests",
                Map.of("expiresInHours", 0)).status()).isEqualTo(422);
    }

    @Test
    void reissuingRevokesTheEarlierTokenAndANewLinkSupersedesTheOld() {
        Issued first = issued();
        Response second = first.owner().post("/api/v1/requirements/" + first.requirementId()
                + "/repository-link-requests", Map.of("expiresInHours", 1));
        assertThat(second.status()).isEqualTo(201);
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        assertThat(redeem(aaron, first.token(), URL).status()).isEqualTo(404);
        Response linked = redeem(aaron, second.body().path("token").asString(), URL);
        assertThat(linked.status()).isEqualTo(201);

        Response third = first.owner().post("/api/v1/requirements/" + first.requirementId()
                + "/repository-link-requests", Map.of());
        Response relinked = redeem(aaron, third.body().path("token").asString(), "https://github.com/acme-bank/other");
        assertThat(relinked.status()).isEqualTo(201);
        assertThat(admin.queryForObject("SELECT status FROM app.requirement_repository_link WHERE id = ?::uuid",
                String.class, linked.body().path("linkId").asString())).isEqualTo("SUPERSEDED");
    }

    @Test
    void invalidUrlIsAValidationErrorAndIssuingInATerminalStateIsAConflict() {
        Issued issued = issued();
        ApiClient aaron = loggedIn("acme", "aaron@acme.test");
        for (String url : List.of("http://github.com/a/b", "https://gitlab.com/a/b", "https://github.com/a/..",
                "https://github.com/-a/b", "https://github.com/a/b/c")) {
            assertThat(redeem(aaron, issued.token(), url).status()).as(url).isEqualTo(422);
        }
        assertThat(issued.owner().post("/api/v1/requirements/" + issued.requirementId() + "/cancel",
                Map.of("reason", "No longer needed by the business"), ifMatch(issued.created()), true).status())
                .isEqualTo(200);
        Response refused = issued.owner().post("/api/v1/requirements/" + issued.requirementId()
                + "/repository-link-requests", Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("LINK_NOT_ALLOWED_IN_STATE");
    }

    @Test
    void concurrentRedemptionHasExactlyOneWinner() throws Exception {
        Issued issued = issued();
        List<ApiClient> approvers = List.of(loggedIn("acme", "aaron@acme.test"), loggedIn("acme", "aaron@acme.test"),
                loggedIn("acme", "aaron@acme.test"), loggedIn("acme", "aaron@acme.test"));
        ExecutorService pool = Executors.newFixedThreadPool(approvers.size());
        try {
            List<Callable<Integer>> calls = approvers.stream()
                    .<Callable<Integer>>map(a -> () -> redeem(a, issued.token(), URL).status()).toList();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 404);
        } finally {
            pool.shutdownNow();
        }
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM app.requirement_repository_link WHERE requirement_id = ?::uuid
                """, Integer.class, issued.requirementId())).isOne();
    }

    @Test
    void databaseRefusesALinkAuthorisedByAClientUserAndTheWorkerHasNoAccess() throws Exception {
        Issued issued = issued();
        UUID tenant = db.tenantId("acme");
        UUID alice = admin.queryForObject("SELECT id FROM app.app_user WHERE email = 'alice@acme.test'", UUID.class);
        try (Connection app = db.appConnection(); Statement statement = app.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> statement.execute("""
                    UPDATE app.repository_link_request SET status = 'USED', used_at = now(), used_by = '%s'
                    WHERE id = '%s'
                    """.formatted(alice, issued.requestId())))).hasMessageContaining("only an active Client Approver");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> statement.execute(
                    "DELETE FROM app.repository_link_request"))).hasMessageContaining("permission denied");
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> statement.execute(
                    "UPDATE app.repository_link_request SET token_hash = repeat('0', 64)")))
                    .hasMessageContaining("permission denied");
        }
        try (Connection worker = db.workerConnection(); Statement statement = worker.createStatement()) {
            for (String table : List.of("repository_link_request", "requirement_repository_link")) {
                assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                        statement.execute("SELECT * FROM app." + table))).hasMessageContaining("permission denied");
            }
        }
    }
}
