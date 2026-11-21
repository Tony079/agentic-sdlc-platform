package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** GD-34 phase 2: pushes of an approved plan to GitHub Issues and the items they created. API and worker. */
@Repository
@Profile("!migrate & !provision")
class PlanPushRepository {

    record RepositoryOption(UUID id, String fullName) {
    }

    record PushRow(UUID id, UUID tenantId, UUID projectId, UUID requirementId, UUID planVersionId, UUID repositoryId,
                   String repository, String status, String payloadJson, int itemsTotal, int created, int updated,
                   int failed, String failureCode, UUID requestedBy, OffsetDateTime requestedAt,
                   OffsetDateTime finishedAt, int planVersionNo) {
    }

    record ItemRow(String kind, String itemKey, int number, String url, UUID lastPushId) {
    }

    private static final String PUSH_SELECT = """
            SELECT p.id, p.tenant_id, p.project_id, p.requirement_id, p.plan_version_id, p.repository_id,
                   r.github_owner || '/' || r.github_name, p.status, p.payload::text, p.items_total, p.items_created,
                   p.items_updated, p.items_failed, p.failure_code, p.requested_by, p.requested_at, p.finished_at,
                   v.version_no
            FROM app.plan_push p JOIN app.repository r ON r.id = p.repository_id
            JOIN app.plan_version v ON v.id = p.plan_version_id
            """;

    private final JdbcClient jdbc;

    PlanPushRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static PushRow push(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new PushRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13),
                rs.getString(14), rs.getObject(15, UUID.class), rs.getObject(16, OffsetDateTime.class),
                rs.getObject(17, OffsetDateTime.class), rs.getInt(18));
    }

    /** The project's verified repositories, the targets a plan can be pushed to. */
    List<RepositoryOption> verifiedRepositories(UUID projectId) {
        return jdbc.sql("""
                SELECT r.id, r.github_owner || '/' || r.github_name
                FROM app.project_repository pr JOIN app.repository r ON r.id = pr.repository_id
                WHERE pr.project_id = :p AND r.status = 'VERIFIED'
                ORDER BY lower(r.github_owner), lower(r.github_name)
                """).param("p", projectId)
                .query((rs, n) -> new RepositoryOption(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    UUID insert(UUID tenantId, UUID projectId, UUID requirementId, UUID planVersionId, UUID repositoryId,
                String payloadJson, int items, UUID requestedBy) {
        return jdbc.sql("""
                INSERT INTO app.plan_push (tenant_id, project_id, requirement_id, plan_version_id, repository_id, payload,
                                           items_total, requested_by)
                VALUES (:t, :p, :r, :v, :repo, :payload::jsonb, :items, :by) RETURNING id
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("v", planVersionId)
                .param("repo", repositoryId).param("payload", payloadJson).param("items", items)
                .param("by", requestedBy).query(UUID.class).single();
    }

    Optional<PushRow> find(UUID id, boolean lock) {
        return jdbc.sql(PUSH_SELECT + "WHERE p.id = :id" + (lock ? " FOR UPDATE OF p" : "")).param("id", id)
                .query(PlanPushRepository::push).optional();
    }

    boolean running(UUID requirementId, UUID repositoryId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app.plan_push WHERE requirement_id = :r AND repository_id = :repo
                               AND status IN ('QUEUED', 'RUNNING'))
                """).param("r", requirementId).param("repo", repositoryId).query(Boolean.class).single();
    }

    /** The newest pushes of the requirement, most recent first. */
    List<PushRow> recent(UUID requirementId, int limit) {
        return jdbc.sql(PUSH_SELECT + "WHERE p.requirement_id = :r ORDER BY p.requested_at DESC LIMIT :limit")
                .param("r", requirementId).param("limit", limit).query(PlanPushRepository::push).list();
    }

    /** GD-36: the newest push of the requirement to the repository that pushed something. */
    Optional<PushRow> latestPushed(UUID requirementId, UUID repositoryId) {
        return jdbc.sql(PUSH_SELECT + """
                WHERE p.requirement_id = :r AND p.repository_id = :repo AND p.status IN ('DONE', 'PARTIAL')
                ORDER BY p.requested_at DESC LIMIT 1
                """).param("r", requirementId).param("repo", repositoryId).query(PlanPushRepository::push).optional();
    }

    /** GD-36: the requirement's newest push that pushed something, to any repository. */
    Optional<PushRow> latestPushedAny(UUID requirementId) {
        return jdbc.sql(PUSH_SELECT + """
                WHERE p.requirement_id = :r AND p.status IN ('DONE', 'PARTIAL') ORDER BY p.requested_at DESC LIMIT 1
                """).param("r", requirementId).query(PlanPushRepository::push).optional();
    }

    /** What Garuda created in the repository for the requirement. */
    List<ItemRow> items(UUID requirementId, UUID repositoryId) {
        return jdbc.sql("""
                SELECT kind, item_key, external_number, external_url, last_push_id FROM app.plan_push_item
                WHERE requirement_id = :r AND repository_id = :repo ORDER BY kind, item_key
                """).param("r", requirementId).param("repo", repositoryId)
                .query((rs, n) -> new ItemRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getObject(5, UUID.class))).list();
    }

    /** Story key to issue URL, for the repository pushed to most recently. */
    Map<String, String> issueUrls(UUID requirementId, UUID repositoryId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ItemRow i : items(requirementId, repositoryId)) {
            if ("ISSUE".equals(i.kind())) {
                out.put(i.itemKey(), i.url());
            }
        }
        return out;
    }

    void markRunning(UUID pushId) {
        jdbc.sql("UPDATE app.plan_push SET status = 'RUNNING' WHERE id = :id AND status = 'QUEUED'")
                .param("id", pushId).update();
    }

    /** Records an item as soon as GitHub returns it, and counts it on the push. */
    void record(PushRow push, String kind, String itemKey, int number, String url, boolean created) {
        jdbc.sql("""
                INSERT INTO app.plan_push_item (tenant_id, project_id, requirement_id, repository_id, kind, item_key,
                                                external_number, external_url, last_push_id)
                VALUES (:t, :p, :r, :repo, :kind, :key, :n, :url, :push)
                ON CONFLICT (requirement_id, repository_id, kind, item_key)
                DO UPDATE SET external_number = EXCLUDED.external_number, external_url = EXCLUDED.external_url,
                              last_push_id = EXCLUDED.last_push_id, pushed_at = clock_timestamp()
                """).param("t", push.tenantId()).param("p", push.projectId()).param("r", push.requirementId())
                .param("repo", push.repositoryId()).param("kind", kind).param("key", itemKey).param("n", number)
                .param("url", url).param("push", push.id()).update();
        jdbc.sql("UPDATE app.plan_push SET " + (created ? "items_created = items_created + 1"
                        : "items_updated = items_updated + 1") + " WHERE id = :id").param("id", push.id()).update();
    }

    void finish(UUID pushId, String status, int failed, String failureCode) {
        jdbc.sql("""
                UPDATE app.plan_push SET status = :s, items_failed = :f, failure_code = :code, finished_at = clock_timestamp()
                WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
                """).param("s", status).param("f", failed).param("code", failureCode).param("id", pushId).update();
    }
}
