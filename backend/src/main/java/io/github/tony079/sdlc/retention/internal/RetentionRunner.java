package io.github.tony079.sdlc.retention.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.tony079.sdlc.storage.api.ObjectStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Command line of the one-shot {@code retention} process. Usage:
 * <pre>
 * apply                                           apply the policy to every tenant (schedule daily)
 * set-policy --tenant=SLUG --class=SNAPSHOT|DOCUMENT|BRD|AUDIT --days=N
 * delete-tenant-data --tenant=SLUG --confirm=SLUG  FR-RET-02 tenant deletion procedure
 * </pre>
 */
@Component
@Profile("retention")
class RetentionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);
    private final RetentionService service;

    RetentionRunner(JdbcClient jdbc, PlatformTransactionManager transactionManager, ObjectStore storage,
                    @Value("${sdlc.retention.default-days.snapshot}") int snapshot,
                    @Value("${sdlc.retention.default-days.document}") int document,
                    @Value("${sdlc.retention.default-days.brd}") int brd,
                    @Value("${sdlc.retention.default-days.audit}") int audit) {
        this.service = new RetentionService(jdbc, transactionManager, storage,
                Map.of("SNAPSHOT", snapshot, "DOCUMENT", document, "BRD", brd, "AUDIT", audit));
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> commands = args.getNonOptionArgs();
        if (commands.size() != 1) {
            throw new IllegalArgumentException("exactly one retention command is required");
        }
        switch (commands.get(0)) {
            case "apply" -> log.info("Retention totals: {}", service.applyAll());
            case "set-policy" -> service.setPolicy(one(args, "tenant"), one(args, "class").toUpperCase(Locale.ROOT),
                    Integer.parseInt(one(args, "days")));
            case "delete-tenant-data" -> {
                String tenant = one(args, "tenant");
                if (!tenant.equals(one(args, "confirm"))) {
                    throw new IllegalArgumentException("--confirm must repeat the tenant slug");
                }
                log.info("Tenant data deleted: {}", service.deleteTenantData(tenant));
            }
            default -> throw new IllegalArgumentException("unknown retention command");
        }
    }

    private static String one(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required exactly once");
        }
        return values.get(0);
    }
}
