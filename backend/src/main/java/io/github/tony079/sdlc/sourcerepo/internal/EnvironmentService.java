package io.github.tony079.sdlc.sourcerepo.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.identity.api.ClientUsers;
import io.github.tony079.sdlc.identity.api.CurrentUser;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.outbox.api.OutboxPublisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Project environments (GD-20, GD-21). The project's Environment Manager connects GitHub repositories (the Garuda
 * GitHub App, installed by the client, gives read-only access) and defines environments with a branch per repository.
 * Changes to the PROD environment become requests that a BRD Approver of the project approves. The worker verifies
 * repositories and branches (ENV_VERIFY). All rules are enforced again by the database.
 */
@Service
@ConditionalOnWebApplication
class EnvironmentService {

    private static final Pattern REPO = Pattern.compile(
            "^(?:https?://github\\.com/)?([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");

    record Repository(UUID id, String fullName, String url, String status, String statusReason, String defaultBranch) {
    }

    record Branch(UUID id, UUID repositoryId, String repository, String branch, String status, String headSha,
                  String failureCode, OffsetDateTime verifiedAt) {
    }

    record Environment(UUID id, String name, String description, boolean production, List<Branch> branches) {
    }

    record ChangeRequest(UUID id, UUID environmentId, String environmentName, String kind, UUID repositoryId,
                         String repository, String branch, String newName, String reason, String status,
                         UUID requestedBy, String requestedByName, OffsetDateTime requestedAt, String approvedByName,
                         OffsetDateTime executedAt) {
    }

    record Overview(UUID projectId, String projectStatus, UUID environmentManagerId, String environmentManagerName,
                    boolean canManage, boolean canApproveProduction, List<Repository> repositories,
                    List<Environment> environments, List<ChangeRequest> requests) {
    }

    /** The result of a change: applied at once, or a PROD request waiting for a BRD Approver. */
    record Result(boolean applied, UUID requestId) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OutboxPublisher outbox;

    EnvironmentService(JdbcClient jdbc, PlatformTransactionManager transactionManager, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
    }

    Overview overview(UUID projectId) {
        CurrentUser me = ClientUsers.require();
        return tx.execute(s -> {
            Object[] project = project(projectId);
            boolean manager = me.userId().equals(project[1]) && "ACTIVE".equals(project[0]);
            boolean approver = jdbc.sql("SELECT app.is_brd_approver(:p)").param("p", projectId).query(Boolean.class).single();
            List<Repository> repositories = jdbc.sql("""
                    SELECT r.id, r.github_owner || '/' || r.github_name, r.repository_url, r.status, r.status_reason,
                           r.default_branch
                    FROM app.project_repository pr JOIN app.repository r ON r.id = pr.repository_id
                    WHERE pr.project_id = :p ORDER BY lower(r.github_owner), lower(r.github_name)
                    """).param("p", projectId).query((rs, n) -> new Repository(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))).list();
            Map<UUID, List<Branch>> byEnvironment = new java.util.HashMap<>();
            jdbc.sql("""
                    SELECT b.id, b.repository_id, r.github_owner || '/' || r.github_name, b.branch, b.status, b.head_sha,
                           b.failure_code, b.verified_at, b.environment_id
                    FROM app.environment_branch b JOIN app.repository r ON r.id = b.repository_id
                    WHERE b.project_id = :p ORDER BY lower(r.github_owner), lower(r.github_name)
                    """).param("p", projectId).query((rs, n) -> {
                        byEnvironment.computeIfAbsent(rs.getObject(9, UUID.class), k -> new java.util.ArrayList<>())
                                .add(new Branch(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                        rs.getObject(8, OffsetDateTime.class)));
                        return 0;
                    }).list();
            List<Environment> environments = jdbc.sql("""
                    SELECT id, name, description, is_production FROM app.environment WHERE project_id = :p
                    ORDER BY is_production DESC, lower(name)
                    """).param("p", projectId).query((rs, n) -> {
                        UUID id = rs.getObject(1, UUID.class);
                        return new Environment(id, rs.getString(2), rs.getString(3), rs.getBoolean(4),
                                byEnvironment.getOrDefault(id, List.of()));
                    }).list();
            List<ChangeRequest> requests = jdbc.sql(REQUEST_SELECT + " WHERE c.project_id = :p ORDER BY c.status <> 'REQUESTED', c.requested_at DESC LIMIT 50")
                    .param("p", projectId).query((rs, n) -> request(rs)).list();
            return new Overview(projectId, (String) project[0], (UUID) project[1], (String) project[2], manager, approver,
                    repositories, environments, requests);
        });
    }

    /** Connects a GitHub repository (owner/name or URL) to the project; the worker verifies the App installation. */
    Repository connect(UUID projectId, String value) {
        Matcher m = REPO.matcher(value == null ? "" : value.strip());
        if (!m.matches()) {
            throw ApiException.validation("Enter the repository as owner/name, for example acme/payments-api.");
        }
        String owner = m.group(1);
        String name = m.group(2);
        CurrentUser me = ClientUsers.require();
        UUID repositoryId = guarded(() -> tx.execute(s -> {
            requireManager(projectId, me);
            UUID id = jdbc.sql("""
                    SELECT id FROM app.repository WHERE lower(github_owner) = lower(:o) AND lower(github_name) = lower(:n)
                    """).param("o", owner).param("n", name).query(UUID.class).optional().orElse(null);
            if (id == null) {
                id = jdbc.sql("""
                        INSERT INTO app.repository (tenant_id, github_owner, github_name, repository_url, registered_by_user)
                        VALUES (:t, :o, :n, :url, :me) RETURNING id
                        """).param("t", me.tenantId()).param("o", owner).param("n", name)
                        .param("url", "https://github.com/" + owner + "/" + name).param("me", me.userId())
                        .query(UUID.class).single();
            }
            int linked = jdbc.sql("""
                    INSERT INTO app.project_repository (tenant_id, project_id, repository_id) VALUES (:t, :p, :r)
                    ON CONFLICT DO NOTHING
                    """).param("t", me.tenantId()).param("p", projectId).param("r", id).update();
            if (linked == 0) {
                throw ApiException.conflict("REPOSITORY_LINKED", "This repository is already connected to the project.");
            }
            verify(id);
            return id;
        }));
        return overview(projectId).repositories().stream().filter(r -> r.id().equals(repositoryId)).findFirst().orElseThrow();
    }

    Result createEnvironment(UUID projectId, String name, String description) {
        CurrentUser me = ClientUsers.require();
        String n = name(name);
        guarded(() -> tx.executeWithoutResult(s -> {
            requireManager(projectId, me);
            jdbc.sql("""
                    INSERT INTO app.environment (tenant_id, project_id, name, description, created_by)
                    VALUES (:t, :p, :name, :d, :me)
                    """).param("t", me.tenantId()).param("p", projectId).param("name", n).param("d", text(description, 500))
                    .param("me", me.userId()).update();
        }));
        return new Result(true, null);
    }

    Result renameEnvironment(UUID environmentId, String name, String description, String reason) {
        CurrentUser me = ClientUsers.require();
        String n = name(name);
        return guarded(() -> tx.execute(s -> {
            Object[] env = environment(environmentId);
            requireManager((UUID) env[0], me);
            if ((Boolean) env[1]) {
                return new Result(false, request((UUID) env[0], environmentId, "RENAME_ENVIRONMENT", null, null, n, reason, me));
            }
            jdbc.sql("UPDATE app.environment SET name = :n, description = :d WHERE id = :id").param("n", n)
                    .param("d", text(description, 500)).param("id", environmentId).update();
            return new Result(true, null);
        }));
    }

    Result removeEnvironment(UUID environmentId, String reason) {
        CurrentUser me = ClientUsers.require();
        return guarded(() -> tx.execute(s -> {
            Object[] env = environment(environmentId);
            requireManager((UUID) env[0], me);
            if ((Boolean) env[1]) {
                return new Result(false, request((UUID) env[0], environmentId, "REMOVE_ENVIRONMENT", null, null, null, reason, me));
            }
            jdbc.sql("UPDATE app.requirement SET target_environment_id = NULL WHERE target_environment_id = :id")
                    .param("id", environmentId).update();
            jdbc.sql("DELETE FROM app.environment WHERE id = :id").param("id", environmentId).update();
            return new Result(true, null);
        }));
    }

    /** Marking an environment as PROD is always a request (it changes which branches agents must never push to). */
    Result markProduction(UUID environmentId, String reason) {
        CurrentUser me = ClientUsers.require();
        return guarded(() -> tx.execute(s -> {
            Object[] env = environment(environmentId);
            requireManager((UUID) env[0], me);
            if ((Boolean) env[1]) {
                throw ApiException.conflict("ALREADY_PRODUCTION", "This environment is already the PROD environment.");
            }
            return new Result(false, request((UUID) env[0], environmentId, "MARK_PRODUCTION", null, null, null, reason, me));
        }));
    }

    Result addBranch(UUID environmentId, UUID repositoryId, String branch, String reason) {
        CurrentUser me = ClientUsers.require();
        String b = branch(branch);
        return guarded(() -> tx.execute(s -> {
            Object[] env = environment(environmentId);
            UUID project = (UUID) env[0];
            requireManager(project, me);
            if ((Boolean) env[1]) {
                return new Result(false, request(project, environmentId, "ADD_BRANCH", repositoryId, b, null, reason, me));
            }
            insertBranch(me, project, environmentId, repositoryId, b);
            return new Result(true, null);
        }));
    }

    Result changeBranch(UUID branchId, String branch, String reason) {
        CurrentUser me = ClientUsers.require();
        String b = branch(branch);
        return guarded(() -> tx.execute(s -> {
            Object[] row = branchRow(branchId);
            UUID project = (UUID) row[0];
            requireManager(project, me);
            if ((Boolean) row[2]) {
                return new Result(false, request(project, (UUID) row[1], "CHANGE_BRANCH", (UUID) row[3], b, null, reason, me));
            }
            jdbc.sql("UPDATE app.environment_branch SET branch = :b WHERE id = :id").param("b", b).param("id", branchId).update();
            verify((UUID) row[3]);
            return new Result(true, null);
        }));
    }

    Result removeBranch(UUID branchId, String reason) {
        CurrentUser me = ClientUsers.require();
        return guarded(() -> tx.execute(s -> {
            Object[] row = branchRow(branchId);
            UUID project = (UUID) row[0];
            requireManager(project, me);
            if ((Boolean) row[2]) {
                return new Result(false, request(project, (UUID) row[1], "REMOVE_BRANCH", (UUID) row[3], null, null, reason, me));
            }
            jdbc.sql("DELETE FROM app.environment_branch WHERE id = :id").param("id", branchId).update();
            return new Result(true, null);
        }));
    }

    /** Asks the worker to check every repository and branch of the project again. */
    void reverify(UUID projectId) {
        CurrentUser me = ClientUsers.require();
        tx.executeWithoutResult(s -> {
            requireManager(projectId, me);
            jdbc.sql("""
                    UPDATE app.environment_branch SET status = 'PENDING_VERIFICATION', head_sha = NULL, verified_at = NULL,
                           failure_code = NULL WHERE project_id = :p
                    """).param("p", projectId).update();
            for (UUID repo : jdbc.sql("SELECT repository_id FROM app.project_repository WHERE project_id = :p")
                    .param("p", projectId).query(UUID.class).list()) {
                verify(repo);
            }
        });
    }

    /** A BRD Approver of the project (not the requester) approves a PROD change; it is applied in this transaction. */
    Result decide(UUID requestId, String action) {
        CurrentUser me = ClientUsers.require();
        return guarded(() -> tx.execute(s -> {
            ChangeRequest r = jdbc.sql(REQUEST_SELECT + " WHERE c.id = :id").param("id", requestId).query((rs, n) -> request(rs))
                    .optional().orElseThrow(ApiException::notFound);
            if (!"REQUESTED".equals(r.status())) {
                throw ApiException.conflict("REQUEST_STATE", "This request has already been decided.");
            }
            UUID project = jdbc.sql("SELECT project_id FROM app.environment_change_request WHERE id = :id").param("id", requestId)
                    .query(UUID.class).single();
            switch (action) {
                case "cancel" -> {
                    if (!me.userId().equals(r.requestedBy())) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "REQUESTER_REQUIRED", "Only the requester can cancel.");
                    }
                    jdbc.sql("""
                            UPDATE app.environment_change_request SET status = 'CANCELLED', decided_by = :me,
                                   decided_at = clock_timestamp() WHERE id = :id
                            """).param("me", me.userId()).param("id", requestId).update();
                    return new Result(false, requestId);
                }
                case "reject", "approve" -> {
                    if (!jdbc.sql("SELECT app.is_brd_approver(:p)").param("p", project).query(Boolean.class).single()) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "BRD_APPROVER_REQUIRED",
                                "Only a BRD Approver of the project can decide PROD changes.");
                    }
                    if ("reject".equals(action)) {
                        jdbc.sql("""
                                UPDATE app.environment_change_request SET status = 'REJECTED', decided_by = :me,
                                       decided_at = clock_timestamp() WHERE id = :id
                                """).param("me", me.userId()).param("id", requestId).update();
                        return new Result(false, requestId);
                    }
                    if (me.userId().equals(r.requestedBy())) {
                        throw new ApiException(HttpStatus.FORBIDDEN, "SECOND_APPROVER_REQUIRED",
                                "A different BRD Approver must approve a PROD change you requested.");
                    }
                    jdbc.sql("""
                            UPDATE app.environment_change_request SET status = 'EXECUTED', approved_by = :me,
                                   executed_at = clock_timestamp() WHERE id = :id
                            """).param("me", me.userId()).param("id", requestId).update();
                    apply(r, project, me);
                    return new Result(true, requestId);
                }
                default -> throw ApiException.notFound();
            }
        }));
    }

    /** The environment a requirement targets, and whether the caller (its owner) may change it. */
    Map<String, Object> targetEnvironment(UUID requirementId) {
        CurrentUser me = ClientUsers.require();
        return tx.execute(s -> jdbc.sql("""
                SELECT r.target_environment_id, r.created_by = :me AS mine, r.project_id
                FROM app.requirement r WHERE r.id = :id""")
                .param("id", requirementId).param("me", me.userId())
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("environmentId", rs.getObject(1, UUID.class));
                    m.put("canChange", rs.getBoolean(2));
                    m.put("projectId", rs.getObject(3, UUID.class));
                    return m;
                }).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Requirement not found.")));
    }

    /** A requirement's owner names the environment it targets (existing products). */
    void setTargetEnvironment(UUID requirementId, UUID environmentId) {
        CurrentUser me = ClientUsers.require();
        tx.executeWithoutResult(s -> {
            UUID project = jdbc.sql("SELECT project_id FROM app.requirement WHERE id = :id AND created_by = :me")
                    .param("id", requirementId).param("me", me.userId()).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "OWNER_REQUIRED",
                            "Only the requirement's owner can choose its environment."));
            if (environmentId != null && !jdbc.sql("SELECT EXISTS (SELECT 1 FROM app.environment WHERE id = :e AND project_id = :p)")
                    .param("e", environmentId).param("p", project).query(Boolean.class).single()) {
                throw ApiException.validation("Choose an environment of the requirement's project.");
            }
            jdbc.sql("UPDATE app.requirement SET target_environment_id = :e WHERE id = :id").param("e", environmentId)
                    .param("id", requirementId).update();
        });
    }

    // --- internals ------------------------------------------------------------------------------------------

    private void apply(ChangeRequest r, UUID project, CurrentUser me) {
        switch (r.kind()) {
            case "MARK_PRODUCTION" -> jdbc.sql("UPDATE app.environment SET is_production = true WHERE id = :id")
                    .param("id", r.environmentId()).update();
            case "RENAME_ENVIRONMENT" -> jdbc.sql("UPDATE app.environment SET name = :n WHERE id = :id")
                    .param("n", r.newName()).param("id", r.environmentId()).update();
            case "REMOVE_ENVIRONMENT" -> {
                jdbc.sql("UPDATE app.requirement SET target_environment_id = NULL WHERE target_environment_id = :id")
                        .param("id", r.environmentId()).update();
                jdbc.sql("DELETE FROM app.environment WHERE id = :id").param("id", r.environmentId()).update();
            }
            case "ADD_BRANCH" -> {
                UUID requester = r.requestedBy();
                jdbc.sql("""
                        INSERT INTO app.environment_branch (tenant_id, project_id, environment_id, repository_id, branch, added_by)
                        VALUES (:t, :p, :e, :r, :b, :by)
                        """).param("t", me.tenantId()).param("p", project).param("e", r.environmentId())
                        .param("r", r.repositoryId()).param("b", r.branch()).param("by", requester).update();
                verify(r.repositoryId());
            }
            case "CHANGE_BRANCH" -> {
                jdbc.sql("UPDATE app.environment_branch SET branch = :b WHERE environment_id = :e AND repository_id = :r")
                        .param("b", r.branch()).param("e", r.environmentId()).param("r", r.repositoryId()).update();
                verify(r.repositoryId());
            }
            case "REMOVE_BRANCH" -> jdbc.sql("DELETE FROM app.environment_branch WHERE environment_id = :e AND repository_id = :r")
                    .param("e", r.environmentId()).param("r", r.repositoryId()).update();
            default -> throw new IllegalStateException(r.kind());
        }
    }

    private void insertBranch(CurrentUser me, UUID project, UUID environmentId, UUID repositoryId, String branch) {
        try {
            jdbc.sql("""
                    INSERT INTO app.environment_branch (tenant_id, project_id, environment_id, repository_id, branch, added_by)
                    VALUES (:t, :p, :e, :r, :b, :me)
                    """).param("t", me.tenantId()).param("p", project).param("e", environmentId).param("r", repositoryId)
                    .param("b", branch).param("me", me.userId()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("BRANCH_EXISTS", "This environment already has a branch for that repository.");
        }
        verify(repositoryId);
    }

    private UUID request(UUID project, UUID environmentId, String kind, UUID repositoryId, String branch, String newName,
                         String reason, CurrentUser me) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() < 5 || text.length() > 2000) {
            throw ApiException.validation("Changes to PROD need a reason (5 to 2000 characters) for the approver.");
        }
        if ("ADD_BRANCH".equals(kind) && jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM app.environment_branch WHERE environment_id = :e AND repository_id = :r)
                """).param("e", environmentId).param("r", repositoryId).query(Boolean.class).single()) {
            throw ApiException.conflict("BRANCH_EXISTS", "This environment already has a branch for that repository.");
        }
        return jdbc.sql("""
                INSERT INTO app.environment_change_request (tenant_id, project_id, environment_id, kind, repository_id,
                       branch, new_name, reason, requested_by)
                VALUES (:t, :p, :e, :k, :r, :b, :n, :reason, :me) RETURNING id
                """).param("t", me.tenantId()).param("p", project).param("e", environmentId).param("k", kind)
                .param("r", repositoryId).param("b", branch).param("n", newName).param("reason", text).param("me", me.userId())
                .query(UUID.class).single();
    }

    private void verify(UUID repositoryId) {
        outbox.publish(RegistrationService.AGGREGATE, repositoryId, EventTypes.ENVIRONMENT_BRANCHES_CHANGED,
                Map.of("repositoryId", repositoryId.toString()));
    }

    private Object[] project(UUID projectId) {
        return jdbc.sql("""
                SELECT p.status, p.environment_manager_id, (SELECT u.display_name FROM app.app_user u WHERE u.id = p.environment_manager_id)
                FROM app.project p WHERE p.id = :p
                """).param("p", projectId).query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class),
                rs.getString(3)}).optional().orElseThrow(ApiException::notFound);
    }

    private Object[] environment(UUID environmentId) {
        return jdbc.sql("SELECT project_id, is_production FROM app.environment WHERE id = :id").param("id", environmentId)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getBoolean(2)}).optional()
                .orElseThrow(ApiException::notFound);
    }

    private Object[] branchRow(UUID branchId) {
        return jdbc.sql("""
                SELECT b.project_id, b.environment_id, e.is_production, b.repository_id
                FROM app.environment_branch b JOIN app.environment e ON e.id = b.environment_id WHERE b.id = :id
                """).param("id", branchId).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getBoolean(3), rs.getObject(4, UUID.class)}).optional().orElseThrow(ApiException::notFound);
    }

    private void requireManager(UUID projectId, CurrentUser me) {
        Object[] p = project(projectId);
        if (!"ACTIVE".equals(p[0])) {
            throw ApiException.conflict("PROJECT_NOT_ACTIVE", "Environments can be set up once Garuda has activated the project.");
        }
        if (!me.userId().equals(p[1])) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ENVIRONMENT_MANAGER_REQUIRED",
                    "Only the project's Environment Manager can change environments and repositories.");
        }
    }

    private static <T> T guarded(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE", "An environment with this name already exists in the project.");
        } catch (DataAccessException e) {
            String text = Optional.ofNullable(e.getMostSpecificCause().getMessage()).orElse("").split("\n")[0]
                    .replace("ERROR: ", "");
            throw ApiException.conflict("ENVIRONMENT_CHANGE_REFUSED", text);
        }
    }

    private static void guarded(Runnable action) {
        guarded(() -> {
            action.run();
            return null;
        });
    }

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) {
            throw ApiException.validation("Give the environment a name of up to 60 characters, for example QC or Pre-prod.");
        }
        return n;
    }

    private static String branch(String branch) {
        String b = branch == null ? "" : branch.strip();
        if (b.isEmpty() || b.length() > 255 || b.contains("..") || b.startsWith("/") || b.endsWith("/")
                || !b.matches("^[^\\s~^:?*\\[\\\\\\p{Cntrl}]+$")) {
            throw ApiException.validation("Enter a valid branch name, for example release/qc.");
        }
        return b;
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String t = value.strip();
        if (t.length() > max) {
            throw ApiException.validation("The description is too long.");
        }
        return t;
    }

    private static final String REQUEST_SELECT = """
            SELECT c.id, c.environment_id, e.name, c.kind, c.repository_id,
                   (SELECT r.github_owner || '/' || r.github_name FROM app.repository r WHERE r.id = c.repository_id),
                   c.branch, c.new_name, c.reason, c.status, c.requested_by, rq.display_name, c.requested_at,
                   (SELECT u.display_name FROM app.app_user u WHERE u.id = c.approved_by), c.executed_at
            FROM app.environment_change_request c
            LEFT JOIN app.environment e ON e.id = c.environment_id
            JOIN app.app_user rq ON rq.id = c.requested_by
            """;

    private static ChangeRequest request(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChangeRequest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getObject(11, UUID.class), rs.getString(12), rs.getObject(13, OffsetDateTime.class),
                rs.getString(14), rs.getObject(15, OffsetDateTime.class));
    }
}
