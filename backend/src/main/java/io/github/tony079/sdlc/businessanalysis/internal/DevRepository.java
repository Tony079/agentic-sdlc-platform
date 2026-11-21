package io.github.tony079.sdlc.businessanalysis.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** GD-36: development runs and their story tasks. Written by the worker; people only pause, resume, retry, skip. */
@Repository
@Profile("!migrate & !provision")
class DevRepository {

    record RunRow(UUID id, UUID tenantId, UUID projectId, UUID requirementId, UUID planVersionId, UUID repositoryId,
                  String status, int currentWave, int agentSlots, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                  String repository) {
    }

    record TaskRow(UUID id, UUID devRunId, String storyKey, String title, int wave, boolean external,
                   Integer issueNumber, String status, int attempts, String branch, String baseSha, String headSha,
                   Integer prNumber, String prUrl, String summary, Boolean testsPassed, Integer filesChanged,
                   String failureCode, OffsetDateTime updatedAt) {
    }

    private static final String RUN_SELECT = """
            SELECT d.id, d.tenant_id, d.project_id, d.requirement_id, d.plan_version_id, d.repository_id, d.status,
                   d.current_wave, d.agent_slots, d.started_at, d.finished_at, r.github_owner || '/' || r.github_name
            FROM app.dev_run d JOIN app.repository r ON r.id = d.repository_id
            """;
    private static final String TASK_SELECT = """
            SELECT id, dev_run_id, story_key, title, wave, external, issue_number, status, attempts, branch, base_sha,
                   head_sha, pr_number, pr_url, summary, tests_passed, files_changed, failure_code, updated_at
            FROM app.dev_task
            """;

    private final JdbcClient jdbc;

    DevRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static RunRow run(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new RunRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7),
                rs.getInt(8), rs.getInt(9), rs.getObject(10, OffsetDateTime.class),
                rs.getObject(11, OffsetDateTime.class), rs.getString(12));
    }

    private static TaskRow task(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TaskRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getBoolean(6), (Integer) rs.getObject(7), rs.getString(8), rs.getInt(9),
                rs.getString(10), rs.getString(11), rs.getString(12), (Integer) rs.getObject(13), rs.getString(14),
                rs.getString(15), (Boolean) rs.getObject(16), (Integer) rs.getObject(17), rs.getString(18),
                rs.getObject(19, OffsetDateTime.class));
    }

    Optional<RunRow> run(UUID id, boolean lock) {
        return jdbc.sql(RUN_SELECT + "WHERE d.id = :id" + (lock ? " FOR UPDATE OF d" : "")).param("id", id)
                .query(DevRepository::run).optional();
    }

    /** The requirement's newest run. */
    Optional<RunRow> latest(UUID requirementId) {
        return jdbc.sql(RUN_SELECT + "WHERE d.requirement_id = :r ORDER BY d.started_at DESC LIMIT 1")
                .param("r", requirementId).query(DevRepository::run).optional();
    }

    Optional<RunRow> active(UUID requirementId) {
        return jdbc.sql(RUN_SELECT + "WHERE d.requirement_id = :r AND d.status IN ('RUNNING', 'PAUSED')")
                .param("r", requirementId).query(DevRepository::run).optional();
    }

    /** Running runs: run id and requirement id. */
    List<UUID[]> running() {
        return jdbc.sql("SELECT id, requirement_id FROM app.dev_run WHERE status = 'RUNNING'")
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).list();
    }

    UUID insertRun(UUID tenantId, UUID projectId, UUID requirementId, UUID planVersionId, UUID repositoryId,
                   int agentSlots) {
        return jdbc.sql("""
                INSERT INTO app.dev_run (tenant_id, project_id, requirement_id, plan_version_id, repository_id, agent_slots)
                VALUES (:t, :p, :r, :v, :repo, :slots) RETURNING id
                """).param("t", tenantId).param("p", projectId).param("r", requirementId).param("v", planVersionId)
                .param("repo", repositoryId).param("slots", Math.max(1, Math.min(50, agentSlots)))
                .query(UUID.class).single();
    }

    void insertTask(RunRow run, String storyKey, String title, int wave, boolean external, Integer issueNumber) {
        jdbc.sql("""
                INSERT INTO app.dev_task (tenant_id, project_id, requirement_id, dev_run_id, story_key, title, wave,
                                          external, issue_number)
                VALUES (:t, :p, :r, :run, :k, :title, :wave, :external, :issue)
                """).param("t", run.tenantId()).param("p", run.projectId()).param("r", run.requirementId())
                .param("run", run.id()).param("k", storyKey)
                .param("title", title.length() > 300 ? title.substring(0, 300) : title).param("wave", wave)
                .param("external", external).param("issue", issueNumber).update();
    }

    List<TaskRow> tasks(UUID devRunId) {
        return jdbc.sql(TASK_SELECT + "WHERE dev_run_id = :run ORDER BY wave, story_key").param("run", devRunId)
                .query(DevRepository::task).list();
    }

    Optional<TaskRow> task(UUID id, boolean lock) {
        return jdbc.sql(TASK_SELECT + "WHERE id = :id" + (lock ? " FOR UPDATE" : "")).param("id", id)
                .query(DevRepository::task).optional();
    }

    /** Whether a DEV_STORY job for the task is queued or running. */
    boolean liveStoryJob(UUID taskId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app.job WHERE job_type = 'DEV_STORY' AND idempotency_key LIKE :k
                               AND status IN ('QUEUED', 'LEASED'))
                """).param("k", "dev-story:" + taskId + ":%").query(Boolean.class).single();
    }

    void setRun(UUID id, String status, int wave, boolean finished) {
        jdbc.sql("UPDATE app.dev_run SET status = :s, current_wave = :w" + (finished ? ", finished_at = clock_timestamp()"
                : "") + " WHERE id = :id").param("s", status).param("w", wave).param("id", id).update();
    }

    /** People's controls (the database checks the role and the transition). */
    void controlRun(UUID id, String status, UUID by) {
        jdbc.sql("UPDATE app.dev_run SET status = :s, status_by = :by WHERE id = :id").param("s", status)
                .param("by", by).param("id", id).update();
    }

    void controlTask(UUID id, String status, UUID by) {
        jdbc.sql("UPDATE app.dev_task SET status = :s, status_by = :by, failure_code = NULL WHERE id = :id")
                .param("s", status).param("by", by).param("id", id).update();
    }

    void setTask(UUID id, String status, String failureCode) {
        jdbc.sql("UPDATE app.dev_task SET status = :s, failure_code = :f WHERE id = :id").param("s", status)
                .param("f", failureCode).param("id", id).update();
    }

    void startCoding(UUID id) {
        jdbc.sql("UPDATE app.dev_task SET status = 'CODING', attempts = attempts + 1, failure_code = NULL WHERE id = :id")
                .param("id", id).update();
    }

    void pullRequestOpened(UUID id, String branch, String baseSha, String headSha, int prNumber, String prUrl,
                           String summary, Boolean testsPassed, int filesChanged) {
        jdbc.sql("""
                UPDATE app.dev_task SET status = 'PR_OPEN', branch = :branch, base_sha = :base, head_sha = :head,
                       pr_number = :n, pr_url = :url, summary = :summary, tests_passed = :tests,
                       files_changed = :files, failure_code = NULL
                WHERE id = :id
                """).param("branch", branch).param("base", baseSha).param("head", headSha).param("n", prNumber)
                .param("url", prUrl).param("summary", summary).param("tests", testsPassed).param("files", filesChanged)
                .param("id", id).update();
    }

    void failed(UUID id, String code, String summary) {
        jdbc.sql("UPDATE app.dev_task SET status = 'FAILED', failure_code = :code, summary = coalesce(:summary, summary) "
                + "WHERE id = :id").param("code", code).param("summary", summary).param("id", id).update();
    }
}
