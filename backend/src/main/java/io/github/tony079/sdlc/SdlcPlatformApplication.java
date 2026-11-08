package io.github.tony079.sdlc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;

/**
 * Agentic SDLC Platform (modular monolith).
 *
 * <p>Started normally it serves the API. Started with the {@code migrate} profile it applies database
 * migrations (and optional development seed data) as the migrator login, then exits. Started with the {@code provision}
 * profile it runs one staff provisioning command as the provisioning login, then exits.
 */
@SpringBootApplication
public class SdlcPlatformApplication {

    public static void main(String[] args) {
        disableLiquibaseAnalytics();
        ConfigurableApplicationContext context = SpringApplication.run(SdlcPlatformApplication.class, args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("migrate | provision | retention"))) {
            System.exit(SpringApplication.exit(context));
        }
    }

    /** Liquibase must not send usage data from this platform. */
    public static void disableLiquibaseAnalytics() {
        if (System.getProperty("liquibase.analytics.enabled") == null) {
            System.setProperty("liquibase.analytics.enabled", "false");
        }
    }
}
