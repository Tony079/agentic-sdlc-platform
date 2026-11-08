package io.github.tony079.sdlc.common.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import io.github.tony079.sdlc.common.api.SecretsProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code sdlc.secrets.provider=env} (default): {@code SDLC_SECRET_<NAME>} environment variables (dots and dashes become
 * underscores). {@code file}: {@code <sdlc.secrets.directory>/<name>}, regular files only, at most 16 KiB, trailing
 * newline removed. Unknown names, symbolic links and path tricks are refused.
 */
@Component
class ConfiguredSecretsProvider implements SecretsProvider {

    private final String provider;
    private final Path directory;
    private final Environment environment;

    ConfiguredSecretsProvider(@Value("${sdlc.secrets.provider:env}") String provider,
                              @Value("${sdlc.secrets.directory:/run/secrets}") Path directory, Environment environment) {
        if (!"env".equals(provider) && !"file".equals(provider)) {
            throw new IllegalStateException("sdlc.secrets.provider must be env or file");
        }
        this.provider = provider;
        this.directory = directory.toAbsolutePath().normalize();
        this.environment = environment;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public Optional<String> secret(String name) {
        if (name == null || !name.matches("^[a-z0-9][a-z0-9.-]{1,63}$")) {
            throw new IllegalArgumentException("invalid secret name");
        }
        if ("env".equals(provider)) {
            String value = environment.getProperty("SDLC_SECRET_" + name.toUpperCase(Locale.ROOT).replaceAll("[.-]", "_"));
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        }
        Path file = directory.resolve(name).normalize();
        try {
            if (!file.startsWith(directory) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > 16 * 1024) {
                return Optional.empty();
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).stripTrailing();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
