package io.github.tony079.sdlc.common.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** O-12 secrets abstraction: environment and mounted-file providers; names cannot escape the directory. */
class ConfiguredSecretsProviderTest {

    @TempDir
    Path dir;

    @Test
    void readsFromEnvironmentOrMountedFiles() throws Exception {
        var env = new ConfiguredSecretsProvider("env", dir,
                new MockEnvironment().withProperty("SDLC_SECRET_ALERTS_WEBHOOK", "env-value"));
        assertThat(env.secret("alerts.webhook")).contains("env-value");
        assertThat(env.secret("missing")).isEmpty();

        Files.writeString(dir.resolve("llm.anthropic-key"), "file-value\n");
        Files.createSymbolicLink(dir.resolve("link"), dir.resolve("llm.anthropic-key"));
        var file = new ConfiguredSecretsProvider("file", dir, new MockEnvironment());
        assertThat(file.secret("llm.anthropic-key")).contains("file-value");
        assertThat(file.secret("link")).isEmpty();
        assertThatThrownBy(() -> file.secret("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfiguredSecretsProvider("vault", dir, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }
}
