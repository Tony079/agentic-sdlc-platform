package io.github.tony079.sdlc.requirement.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.identity.api.CurrentUser.TenantRole;
import io.github.tony079.sdlc.support.ApiIntegrationTest;
import io.github.tony079.sdlc.support.TestDatabase;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction-level guarantees of key generation and idempotent creation, using the real beans and real
 * concurrent transactions (V-M2-1, V-M2-5): rolled-back creates consume no key; a concurrent same-key create waits
 * for the first and replays its result, or creates if the first rolled back.
 */
class RequirementTransactionIT extends ApiIntegrationTest {

    @Autowired
    RequirementService service;
    @Autowired
    RequirementKeyGenerator keys;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final TestDatabase db = TestDatabase.get();
    private CurrentUser alice;
    private UUID project;

    @BeforeEach
    void signIn() {
        UUID tenant = db.tenantId("acme");
        UUID userId = db.admin().queryForObject("SELECT id FROM app.app_user WHERE email = 'alice@acme.test'", UUID.class);
        alice = new CurrentUser(userId, tenant, "Acme Corporation", "alice@acme.test", "Alice Client",
                TenantRole.MEMBER);
        project = io.github.tony079.sdlc.support.RequirementFixtures.projectFor(tenant, userId, "tx-" + UUID.randomUUID());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rolledBackKeyGenerationConsumesNoNumber() {
        authenticate();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String first = tx.execute(s -> {
            String key = keys.next(alice.tenantId());
            s.setRollbackOnly();
            return key;
        });
        String second = tx.execute(s -> keys.next(alice.tenantId()));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void replaysTheSameRequestAndRejectsAReusedKeyWithADifferentBody() {
        authenticate();
        UUID key = UUID.randomUUID();
        RequirementService.CreateResult first = service.create(project, fields("Same request"), key);
        RequirementService.CreateResult replay = service.create(project, fields("Same request"), key);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.detail().id()).isEqualTo(first.detail().id());
        assertThat(replay.detail().key()).isEqualTo(first.detail().key());

        assertThatThrownBy(() -> service.create(project, fields("Different body"), key))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(rows(key)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyWaitsForTheFirstAndReplaysIt() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch firstCreated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RequirementService.CreateResult> first = pool.submit(() -> asAlice(() ->
                    new TransactionTemplate(transactionManager).execute(s -> {
                        RequirementService.CreateResult r = service.create(project, fields("Concurrent"), key);
                        firstCreated.countDown();
                        await(releaseFirst);
                        return r;
                    })));
            await(firstCreated);
            Future<RequirementService.CreateResult> second = pool.submit(() -> asAlice(() ->
                    service.create(project, fields("Concurrent"), key)));
            Thread.sleep(300); // the second request is now blocked on the first transaction's key claim
            assertThat(second.isDone()).isFalse();
            releaseFirst.countDown();

            RequirementService.CreateResult a = first.get(10, TimeUnit.SECONDS);
            RequirementService.CreateResult b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.replayed()).isFalse();
            assertThat(b.replayed()).isTrue();
            assertThat(b.detail().id()).isEqualTo(a.detail().id());
            assertThat(requirementsWithTitle("Concurrent")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyCreatesWhenTheFirstRollsBack() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch firstCreated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = pool.submit(() -> asAlice(() ->
                    new TransactionTemplate(transactionManager).execute(s -> {
                        RequirementService.CreateResult r = service.create(project, fields("Rolled back"), key);
                        firstCreated.countDown();
                        await(releaseFirst);
                        s.setRollbackOnly();
                        return r.detail().id();
                    })));
            await(firstCreated);
            Future<RequirementService.CreateResult> second = pool.submit(() -> asAlice(() ->
                    service.create(project, fields("Rolled back"), key)));
            Thread.sleep(300);
            releaseFirst.countDown();

            UUID rolledBackId = first.get(10, TimeUnit.SECONDS);
            RequirementService.CreateResult b = second.get(10, TimeUnit.SECONDS);
            assertThat(b.replayed()).isFalse();
            assertThat(b.detail().id()).isNotEqualTo(rolledBackId);
            assertThat(requirementsWithTitle("Rolled back")).isEqualTo(1);
            assertThat(rows(key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private RequirementFields fields(String titlePrefix) {
        return RequirementFields.normalized("NEW_PRODUCT", titlePrefix + " " + project, null, null, null, null, null,
                null, null);
    }

    private int requirementsWithTitle(String titlePrefix) {
        return db.admin().queryForObject("""
                SELECT count(DISTINCT requirement_id) FROM app.requirement_revision WHERE title = ?
                """, Integer.class, titlePrefix + " " + project);
    }

    private int rows(UUID key) {
        return db.admin().queryForObject("SELECT count(*) FROM app.idempotency_record WHERE idem_key = ?",
                Integer.class, key);
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(alice, null, List.of()));
    }

    private <T> T asAlice(java.util.function.Supplier<T> work) {
        authenticate();
        try {
            assertThat(TenantContext.currentTenant()).contains(alice.tenantId());
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
