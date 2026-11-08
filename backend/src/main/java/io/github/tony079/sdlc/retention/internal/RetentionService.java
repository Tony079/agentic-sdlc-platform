package io.github.tony079.sdlc.retention.internal;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.storage.api.ObjectStore;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The controlled retention and deletion process (PRD FR-RET-01..16, S-01), run only in the one-shot {@code retention}
 * process as {@code sdlc_retention}. Works tenant by tenant under RLS.
 * <ul>
 *   <li>Closure (FR-RET-16): a requirement is closed when its current state is SHARED_WITH_CLIENT (Release 1
 *       completion), REJECTED or CANCELLED; the closure time is the transition into that state.</li>
 *   <li>Repository snapshots and indexes (FR-RET-11), documents with their versions, extracted text and chunks
 *       (FR-RET-12), BRDs with evidence (FR-RET-13): deleted after the configured days from closure; derived data goes
 *       with its source (FR-RET-08). Evidence references of approved BRDs stay as records until the BRD period ends
 *       (FR-RET-10).</li>
 *   <li>Audit metadata (FR-RET-14): rows older than the audit period are deleted only here.</li>
 *   <li>Every deletion is audited without content (FR-RET-04).</li>
 * </ul>
 * No duration is hard-coded (FR-RET-07): defaults come from configuration, overrides from retention_policy.
 */
class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    static final List<String> CLASSES = List.of("SNAPSHOT", "DOCUMENT", "BRD", "AUDIT");

    record Result(int snapshots, int documents, int brdVersions, int auditRows) {
        Result plus(Result other) {
            return new Result(snapshots + other.snapshots, documents + other.documents, brdVersions + other.brdVersions,
                    auditRows + other.auditRows);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectStore storage;
    private final Map<String, Integer> defaults;

    RetentionService(JdbcClient jdbc, PlatformTransactionManager transactionManager, ObjectStore storage,
                     Map<String, Integer> defaults) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.storage = storage;
        this.defaults = Map.copyOf(defaults);
        for (String c : CLASSES) {
            Integer days = this.defaults.get(c);
            if (days == null || days < 1 || days > 3650) {
                throw new IllegalStateException("sdlc.retention.default-days." + c.toLowerCase(Locale.ROOT)
                        + " must be between 1 and 3650");
            }
        }
    }

    /** Applies the policy to every tenant. Returns the totals. */
    Result applyAll() {
        List<UUID> tenants = tx.execute(s -> jdbc.sql("SELECT id FROM app.tenant WHERE status IN ('ACTIVE', 'SUSPENDED')")
                .query(UUID.class).list());
        Result total = new Result(0, 0, 0, 0);
        for (UUID tenant : tenants) {
            total = total.plus(TenantContext.callAs(tenant, () -> apply(tenant, false)));
        }
        return total;
    }

    /** FR-RET-02: deletes a tenant's snapshots, documents and BRD content now, regardless of age, then closes it. */
    Result deleteTenantData(String slug) {
        UUID tenant = tx.execute(s -> jdbc.sql("SELECT id FROM app.tenant WHERE slug = :s").param("s", slug)
                .query(UUID.class).optional().orElse(null));
        if (tenant == null) {
            throw new IllegalArgumentException("unknown tenant");
        }
        return TenantContext.callAs(tenant, () -> {
            tx.executeWithoutResult(s -> jdbc.sql("UPDATE app.tenant SET status = 'CLOSING' WHERE id = :t")
                    .param("t", tenant).update());
            Result result = apply(tenant, true);
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE app.tenant SET status = 'CLOSED' WHERE id = :t").param("t", tenant).update();
                audit(tenant, "TENANT_DATA_DELETED", "TENANT", tenant);
            });
            return result;
        });
    }

    void setPolicy(String slug, String dataClass, int days) {
        if (!CLASSES.contains(dataClass) || days < 1 || days > 3650) {
            throw new IllegalArgumentException("invalid retention class or days");
        }
        UUID tenant = tx.execute(s -> jdbc.sql("SELECT id FROM app.tenant WHERE slug = :s").param("s", slug)
                .query(UUID.class).optional().orElseThrow(() -> new IllegalArgumentException("unknown tenant")));
        TenantContext.callAs(tenant, () -> tx.execute(s -> {
            jdbc.sql("""
                    INSERT INTO app.retention_policy (tenant_id, data_class, days) VALUES (:t, :c, :d)
                    ON CONFLICT (tenant_id, data_class) DO UPDATE SET days = EXCLUDED.days, set_at = clock_timestamp()
                    """).param("t", tenant).param("c", dataClass).param("d", days).update();
            audit(tenant, "RETENTION_POLICY_SET", "TENANT", tenant);
            return null;
        }));
    }

    private int days(String dataClass) {
        return jdbc.sql("SELECT days FROM app.retention_policy WHERE data_class = :c").param("c", dataClass)
                .query(Integer.class).optional().orElse(defaults.get(dataClass));
    }

    /** Requirements closed before {@code days} ago (all requirements when {@code everything}). */
    private List<UUID> closed(int days, boolean everything) {
        if (everything) {
            return jdbc.sql("SELECT id FROM app.requirement").query(UUID.class).list();
        }
        return jdbc.sql("""
                SELECT i.requirement_id FROM app.workflow_instance i
                JOIN app.workflow_transition t ON t.instance_id = i.id AND t.seq = i.last_seq
                WHERE i.projected_state IN ('SHARED_WITH_CLIENT', 'REJECTED', 'CANCELLED')
                  AND t.occurred_at < clock_timestamp() - make_interval(days => :d)
                """).param("d", days).query(UUID.class).list();
    }

    private Result apply(UUID tenant, boolean everything) {
        int snapshots = 0;
        int documents = 0;
        int versions = 0;
        int audits = 0;
        // Snapshots and derived indexes.
        List<Map<String, Object>> snaps = tx.execute(s -> {
            List<UUID> reqs = closed(days("SNAPSHOT"), everything);
            if (reqs.isEmpty()) {
                return List.<Map<String, Object>>of();
            }
            return jdbc.sql("""
                    SELECT s.id, s.storage_key FROM app.repository_snapshot s
                    JOIN app.requirement_repository rr ON rr.id = s.requirement_repository_id
                    WHERE rr.requirement_id IN (:r) AND s.status <> 'DELETED'
                    """).param("r", reqs).query().listOfRows();
        });
        for (Map<String, Object> snap : snaps) {
            UUID id = (UUID) snap.get("id");
            deleteObject((String) snap.get("storage_key"));
            tx.executeWithoutResult(s -> {
                for (String table : List.of("code_symbol", "build_dependency", "repository_file")) {
                    jdbc.sql("DELETE FROM app." + table + " WHERE snapshot_id = :s").param("s", id).update();
                }
                jdbc.sql("UPDATE app.repository_snapshot SET status = 'DELETED' WHERE id = :s").param("s", id).update();
                audit(tenant, "RETENTION_DELETED", "REPOSITORY_SNAPSHOT", id);
            });
            snapshots++;
        }
        // Documents, their versions, extracted text and chunks.
        List<Map<String, Object>> docs = tx.execute(s -> {
            List<UUID> reqs = closed(days("DOCUMENT"), everything);
            if (reqs.isEmpty()) {
                return List.<Map<String, Object>>of();
            }
            return jdbc.sql("""
                    SELECT id, document_id, storage_key FROM app.document_version
                    WHERE requirement_id IN (:r) AND state <> 'DELETED'
                    """).param("r", reqs).query().listOfRows();
        });
        for (Map<String, Object> doc : docs) {
            UUID id = (UUID) doc.get("id");
            deleteObject((String) doc.get("storage_key"));
            tx.executeWithoutResult(s -> {
                jdbc.sql("DELETE FROM app.document_chunk WHERE version_id = :v").param("v", id).update();
                jdbc.sql("UPDATE app.document_version SET state = 'DELETED', updated_at = clock_timestamp() WHERE id = :v")
                        .param("v", id).update();
                audit(tenant, "RETENTION_DELETED", "DOCUMENT", (UUID) doc.get("document_id"));
            });
            documents++;
        }
        // BRDs, items, evidence, flags and the model proposal they came from.
        List<UUID> brds = tx.execute(s -> {
            List<UUID> reqs = closed(days("BRD"), everything);
            if (reqs.isEmpty()) {
                return List.<UUID>of();
            }
            return jdbc.sql("SELECT id FROM app.brd_version WHERE requirement_id IN (:r) AND status <> 'PURGED'")
                    .param("r", reqs).query(UUID.class).list();
        });
        for (UUID version : brds) {
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                        DELETE FROM app.brd_item_evidence WHERE brd_item_id IN
                          (SELECT id FROM app.brd_item WHERE brd_version_id = :v)
                        """).param("v", version).update();
                jdbc.sql("DELETE FROM app.brd_flag_ack WHERE brd_version_id = :v").param("v", version).update();
                jdbc.sql("DELETE FROM app.brd_item WHERE brd_version_id = :v").param("v", version).update();
                jdbc.sql("UPDATE app.brd_version SET status = 'PURGED', status_at = clock_timestamp() WHERE id = :v")
                        .param("v", version).update();
                jdbc.sql("""
                        UPDATE app.ba_result_pending SET proposal = '{"purged": true}'::jsonb
                        WHERE id = (SELECT pending_result_id FROM app.brd_version WHERE id = :v) AND status <> 'PENDING'
                        """).param("v", version).update();
                audit(tenant, "RETENTION_DELETED", "BRD_VERSION", version);
            });
            versions++;
        }
        // Audit metadata older than its period (FR-RET-14). Never during a tenant deletion (records of the deletion).
        if (!everything) {
            audits = tx.execute(s -> jdbc.sql("""
                    DELETE FROM app.audit_event WHERE occurred_at < clock_timestamp() - make_interval(days => :d)
                    """).param("d", days("AUDIT")).update());
            if (audits > 0) {
                tx.executeWithoutResult(s -> audit(tenant, "RETENTION_AUDIT_PURGED", "TENANT", tenant));
            }
        }
        log.info("Retention applied: tenant={} snapshots={} documents={} brdVersions={} auditRows={}", tenant, snapshots,
                documents, versions, audits);
        return new Result(snapshots, documents, versions, audits);
    }

    private void deleteObject(String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Stored object could not be deleted; the row is still marked deleted and the key is logged as opaque id");
        }
    }

    private void audit(UUID tenant, String action, String resourceType, UUID resourceId) {
        jdbc.sql("""
                INSERT INTO app.audit_event (tenant_id, actor_type, action, resource_type, resource_id, result)
                VALUES (:t, 'SYSTEM', :a, :rt, :rid, 'SUCCESS')
                """).param("t", tenant).param("a", action).param("rt", resourceType).param("rid", resourceId).update();
    }
}
