package io.github.tony079.sdlc;

import static io.github.tony079.sdlc.support.RequirementFixtures.complete;
import static io.github.tony079.sdlc.support.RequirementFixtures.contentOf;
import static io.github.tony079.sdlc.support.RequirementFixtures.ifMatch;
import static io.github.tony079.sdlc.support.RequirementFixtures.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.tony079.sdlc.support.ApiClient;
import io.github.tony079.sdlc.support.ApiClient.Response;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;

import org.junit.jupiter.api.Test;

/** Key generation and optimistic locking under real concurrent HTTP requests (decision C-03; V-M2-1, V-M2-2). */
class RequirementConcurrencyIT extends ApiIntegrationTest {

    @Test
    void parallelCreatesGetDistinctContiguousKeysPerTenant() throws Exception {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        ApiClient gina = loggedIn("globex", "gina@globex.test");
        String acmeProject = project(alice);
        String globexProject = project(gina);
        long acmeBefore = counter("acme");
        long globexBefore = counter("globex");

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            calls.add(() -> alice.post("/api/v1/requirements", complete(acmeProject)));
        }
        for (int i = 0; i < 20; i++) {
            calls.add(() -> gina.post("/api/v1/requirements", complete(globexProject)));
        }
        List<Response> responses = runTogether(calls);
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));

        assertContiguous("acme", acmeProject, acmeBefore, 30);
        assertContiguous("globex", globexProject, globexBefore, 20);
    }

    @Test
    void concurrentEditsWithTheSameVersionHaveExactlyOneWinner() throws Exception {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();

        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String, Object> edit = contentOf(created);
            edit.put("title", "Concurrent edit " + i);
            calls.add(() -> alice.put("/api/v1/requirements/" + id, edit, ifMatch(created)));
        }
        List<Response> responses = runTogether(calls);
        assertThat(responses.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 409))
                .hasSize(7).allSatisfy(r -> assertThat(r.errorCode()).isEqualTo("VERSION_CHANGED"));
        Response after = alice.get("/api/v1/requirements/" + id);
        assertThat(after.body().path("currentRevisionNo").asInt()).isEqualTo(2);
        assertThat(after.body().path("version").asLong()).isEqualTo(1);
    }

    @Test
    void concurrentSubmitAndEditHaveExactlyOneWinner() throws Exception {
        ApiClient alice = loggedIn("acme", "alice@acme.test");
        Response created = alice.post("/api/v1/requirements", complete(project(alice)));
        String id = created.body().path("id").asString();
        Map<String, Object> edit = contentOf(created);
        edit.put("title", "Racing edit");

        List<Response> responses = runTogether(List.of(
                () -> alice.post("/api/v1/requirements/" + id + "/submit", null, ifMatch(created), true),
                () -> alice.put("/api/v1/requirements/" + id, edit, ifMatch(created))));
        assertThat(responses.stream().filter(r -> r.status() == 200)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 409)).hasSize(1);
        assertThat(alice.get("/api/v1/requirements/" + id).body().path("version").asLong()).isEqualTo(1);
    }

    private static long counter(String slug) {
        TestDatabase db = TestDatabase.get();
        Long value = db.admin().query("SELECT last_value FROM app.requirement_key_counter WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, db.tenantId(slug));
        return value == null ? 0 : value;
    }

    /**
     * The keys created in this test (its own project) are exactly before+1 .. before+count, and the tenant counter
     * advanced by exactly count. Scoped to the project because other tests insert synthetic keys into the tenant.
     */
    private static void assertContiguous(String slug, String projectId, long before, int count) {
        TestDatabase db = TestDatabase.get();
        List<Long> numbers = db.admin().queryForList("""
                SELECT substr(key, 5)::bigint FROM app.requirement WHERE tenant_id = ? AND project_id = ? ORDER BY 1
                """, Long.class, db.tenantId(slug), UUID.fromString(projectId));
        List<Long> expected = new ArrayList<>();
        for (long n = before + 1; n <= before + count; n++) {
            expected.add(n);
        }
        assertThat(numbers).as(slug + " keys").containsExactlyElementsOf(expected);
        assertThat(counter(slug)).isEqualTo(before + count);
    }

    static <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static UUID uuid(Response response) {
        return UUID.fromString(response.body().path("id").asString());
    }
}
