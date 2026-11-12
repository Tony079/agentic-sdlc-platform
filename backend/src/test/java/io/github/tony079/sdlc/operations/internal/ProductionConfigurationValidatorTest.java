package io.github.tony079.sdlc.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import io.github.tony079.sdlc.common.api.SecretsProvider;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** O-12/O-15/C-17: production refuses to start with development-grade storage keys, secrets or controls. */
class ProductionConfigurationValidatorTest {

    private static SecretsProvider provider(String name) {
        return new SecretsProvider() {
            @Override
            public String provider() {
                return name;
            }

            @Override
            public Optional<String> secret(String n) {
                return Optional.empty();
            }
        };
    }

    @Test
    void developmentIsNotChecked() {
        assertThat(new ProductionConfigurationValidator(new MockEnvironment(), provider("env")).check()).isEmpty();
    }

    @Test
    void productionWithDevelopmentSettingsIsRefusedNamingOnlyTheChecks() {
        MockEnvironment env = new MockEnvironment().withProperty("sdlc.environment", "production")
                .withProperty("sdlc.documents.extraction.enabled", "true")
                .withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("sdlc.alerts.webhook.secret", "do-not-print-this-secret");
        var validator = new ProductionConfigurationValidator(env, provider("env"));
        assertThat(validator.check()).containsExactly("STORAGE_KMS_LOCAL", "SECRETS_PROVIDER_NOT_MANAGED",
                "EXTRACTION_SANDBOX_NOT_REQUIRED", "COOKIE_NOT_SECURE", "NO_SECURITY_ALERT_CHANNEL");
        assertThatThrownBy(validator::afterSingletonsInstantiated).hasMessageContaining("STORAGE_KMS_LOCAL")
                .hasMessageNotContaining("do-not-print-this-secret");
    }

    @Test
    void aCompliantProductionConfigurationPasses() {
        MockEnvironment env = new MockEnvironment().withProperty("sdlc.environment", "production")
                .withProperty("sdlc.storage.kms.provider", "managed-kms")
                .withProperty("sdlc.documents.extraction.enabled", "true")
                .withProperty("sdlc.documents.extraction.sandbox-required", "true")
                .withProperty("sdlc.alerts.webhook.url", "https://alerts.example.test/hook");
        assertThat(new ProductionConfigurationValidator(env, provider("file")).check()).isEmpty();
    }

    @Test
    void developmentAdaptersAreRefusedInProduction() {
        MockEnvironment env = new MockEnvironment().withProperty("sdlc.environment", "production")
                .withProperty("sdlc.storage.kms.provider", "managed-kms")
                .withProperty("sdlc.alerts.webhook.url", "https://alerts.example.test/hook")
                .withProperty("sdlc.model.provider", "development-mock")
                .withProperty("sdlc.repository.source", "development");
        assertThat(new ProductionConfigurationValidator(env, provider("file")).check())
                .containsExactly("DEVELOPMENT_MODEL_PROVIDER", "DEVELOPMENT_REPOSITORY_SOURCE");
    }
}
