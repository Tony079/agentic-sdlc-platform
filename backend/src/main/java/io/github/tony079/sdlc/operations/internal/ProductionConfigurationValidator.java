package io.github.tony079.sdlc.operations.internal;

import java.util.ArrayList;
import java.util.List;

import io.github.tony079.sdlc.common.api.SecretsProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed production configuration check (owner decisions O-12, O-15, C-17, 2026-11-08). With
 * {@code sdlc.environment=production} the api and worker refuse to start unless:
 * <ul>
 *   <li>storage uses a managed KMS ({@code sdlc.storage.kms.provider} is not {@code local});</li>
 *   <li>secrets come from mounted files of a secrets service ({@code sdlc.secrets.provider=file}), not environment;</li>
 *   <li>document extraction, if enabled, requires its sandbox;</li>
 *   <li>session cookies are secure;</li>
 *   <li>at least one security-alert channel is configured.</li>
 * </ul>
 * Only the names of failed checks are reported, never values. Development and test environments are not checked.
 */
@Component
class ProductionConfigurationValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationValidator.class);

    private final Environment environment;
    private final SecretsProvider secrets;

    ProductionConfigurationValidator(Environment environment, SecretsProvider secrets) {
        this.environment = environment;
        this.secrets = secrets;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> failures = check();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Production configuration refused: " + String.join(", ", failures));
        }
    }

    List<String> check() {
        if (!"production".equals(environment.getProperty("sdlc.environment", "development"))) {
            return List.of();
        }
        List<String> failures = new ArrayList<>();
        if ("local".equals(environment.getProperty("sdlc.storage.kms.provider", "local"))) {
            failures.add("STORAGE_KMS_LOCAL");
        }
        if (!"file".equals(secrets.provider())) {
            failures.add("SECRETS_PROVIDER_NOT_MANAGED");
        }
        if (environment.getProperty("sdlc.documents.extraction.enabled", Boolean.class, false)
                && !environment.getProperty("sdlc.documents.extraction.sandbox-required", Boolean.class, false)) {
            failures.add("EXTRACTION_SANDBOX_NOT_REQUIRED");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) {
            failures.add("COOKIE_NOT_SECURE");
        }
        if (environment.getProperty("sdlc.alerts.webhook.url", "").isBlank()
                && environment.getProperty("sdlc.alerts.email.host", "").isBlank()) {
            failures.add("NO_SECURITY_ALERT_CHANNEL");
        }
        if (!failures.isEmpty()) {
            log.error("Production configuration checks failed: {}", failures);
        }
        return failures;
    }
}
