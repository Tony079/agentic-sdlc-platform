package io.github.tony079.sdlc.securityscan.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;
import io.github.tony079.sdlc.securityscan.api.SecurityScanProvider;
import io.github.tony079.sdlc.securityscan.api.SecurityScanProvider.Kind;
import io.github.tony079.sdlc.sourcerepo.api.SnapshotContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * O-03: after a snapshot is indexed, the worker runs every configured scan kind against it (job SECURITY_SCAN) and
 * records one run per provider with normalised findings. Providers are replaceable; the release gate reads only the
 * recorded runs. Provider selection: {@code sdlc.security-scan.secret-provider} ({@code builtin}, default, or
 * {@code none}); dependency and SAST providers are not configured until O-03 names products.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!migrate & !provision & !retention")
class SecurityScanHandler {

    static final String JOB_TYPE = "SECURITY_SCAN";
    private static final Logger log = LoggerFactory.getLogger(SecurityScanHandler.class);

    record RunResult(String kind, String provider, String version, OffsetDateTime startedAt,
                     SecurityScanProvider.Result result) {
    }

    record Outcome(UUID snapshotId, String commitSha, List<RunResult> runs) {
    }

    @Bean
    List<SecurityScanProvider> securityScanProviders(
            @Value("${sdlc.security-scan.secret-provider:builtin}") String secretProvider) {
        List<SecurityScanProvider> providers = new ArrayList<>();
        providers.add("builtin".equals(secretProvider) ? new BuiltinSecretScanner()
                : new UnconfiguredScanProvider(Kind.SECRET));
        providers.add(new UnconfiguredScanProvider(Kind.DEPENDENCY));
        providers.add(new UnconfiguredScanProvider(Kind.SAST));
        return List.copyOf(providers);
    }

    @Bean
    JobHandler<Outcome> securityScanJob(List<SecurityScanProvider> securityScanProviders, SnapshotContent content,
                                        JdbcClient jdbc) {
        return new JobHandler<>() {
            @Override
            public String jobType() {
                return JOB_TYPE;
            }

            @Override
            public Outcome execute(ClaimedJob job) throws JobFailure {
                UUID snapshotId = UUID.fromString(String.valueOf(job.payload().get("snapshotId")));
                String commit = content.commitSha(snapshotId).orElse(null);
                if (commit == null) {
                    throw JobFailure.fatal("SNAPSHOT_NOT_AVAILABLE", null);
                }
                SecurityScanProvider.Target target = new SecurityScanProvider.Target() {
                    @Override
                    public UUID snapshotId() {
                        return snapshotId;
                    }

                    @Override
                    public String commitSha() {
                        return commit;
                    }

                    @Override
                    public int forEachFile(Predicate<String> paths, BiConsumer<String, byte[]> visitor)
                            throws java.io.IOException {
                        return content.forEachFile(snapshotId, paths, visitor);
                    }
                };
                List<RunResult> runs = new ArrayList<>();
                for (SecurityScanProvider provider : securityScanProviders) {
                    OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
                    SecurityScanProvider.Result result;
                    try {
                        result = provider.scan(target);
                    } catch (RuntimeException e) {
                        log.warn("Security scan provider {} failed ({})", provider.name(), e.getClass().getSimpleName());
                        result = SecurityScanProvider.Result.failed("PROVIDER_ERROR", 0);
                    }
                    runs.add(new RunResult(provider.kind().name(), provider.name(), provider.version(), started,
                            result));
                }
                return new Outcome(snapshotId, commit, runs);
            }

            @Override
            public void commit(ClaimedJob job, Outcome outcome) {
                for (RunResult run : outcome.runs()) {
                    if (jdbc.sql("""
                            SELECT count(*) FROM app.security_scan_run WHERE snapshot_id = :s AND kind = :k AND provider = :p
                            """).param("s", outcome.snapshotId()).param("k", run.kind()).param("p", run.provider())
                            .query(Integer.class).single() > 0) {
                        continue;
                    }
                    UUID runId = jdbc.sql("""
                            INSERT INTO app.security_scan_run (tenant_id, snapshot_id, commit_sha, kind, provider,
                                provider_version, status, error_code, files_scanned, started_at)
                            VALUES (:t, :s, :c, :k, :p, :v, :status, :code, :files, :started) RETURNING id
                            """).param("t", job.tenantId()).param("s", outcome.snapshotId()).param("c", outcome.commitSha())
                            .param("k", run.kind()).param("p", run.provider()).param("v", run.version())
                            .param("status", run.result().status()).param("code", run.result().errorCode())
                            .param("files", run.result().filesScanned()).param("started", run.startedAt())
                            .query(UUID.class).single();
                    for (SecurityScanProvider.Finding f : run.result().findings()) {
                        jdbc.sql("""
                                INSERT INTO app.security_finding (tenant_id, scan_run_id, severity, rule_id, category, path,
                                                                  line, evidence, fingerprint)
                                VALUES (:t, :r, :sev, :rule, :cat, :path, :line, :ev, :fp)
                                """).param("t", job.tenantId()).param("r", runId).param("sev", f.severity().name())
                                .param("rule", f.ruleId()).param("cat", f.category()).param("path", f.path())
                                .param("line", f.line()).param("ev", f.evidence()).param("fp", f.fingerprint()).update();
                    }
                    log.info("security.scan snapshot={} kind={} provider={} status={} files={} findings={}",
                            outcome.snapshotId(), run.kind(), run.provider(), run.result().status(),
                            run.result().filesScanned(), run.result().findings().size());
                }
            }
        };
    }

    /** RepositorySnapshotIndexed: one SECURITY_SCAN job per snapshot (idempotent key). */
    @Bean
    EventConsumer securityScanEnqueue(JobQueue jobs) {
        return new EventConsumer() {
            @Override
            public String name() {
                return "security-scan-enqueue";
            }

            @Override
            public boolean accepts(String eventType) {
                return EventTypes.REPOSITORY_SNAPSHOT_INDEXED.equals(eventType);
            }

            @Override
            public void handle(DomainEvent event) {
                String snapshot = event.text("snapshotId");
                jobs.enqueue(new JobRequest(JOB_TYPE, "security-scan:" + snapshot, "REQUIREMENT",
                        event.uuid("requirementId"), null, Map.of("snapshotId", snapshot), 90));
            }
        };
    }
}
