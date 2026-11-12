package io.github.tony079.sdlc.sourcerepo.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the repository adapter of the worker from server configuration: {@code sdlc.repository.source=github}
 * (default: the GitHub App broker) or {@code development} (bundled sample repository, local prototypes only). An
 * unknown value stops the worker at start-up.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class RepositoryAccessConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RepositoryAccessConfiguration.class);

    @Bean
    RepositoryAccess repositoryAccess(GitHubBroker broker, @Value("${sdlc.repository.source:github}") String source) {
        return switch (source.strip()) {
            case "github" -> new GitHubRepositoryAccess(broker);
            case "development" -> {
                log.warn("DEVELOPMENT repository source is active: repositories of owner '{}' are served from the "
                        + "bundled sample, not from GitHub. Never use this outside a local prototype.",
                        DevelopmentRepositoryAccess.OWNER);
                yield new DevelopmentRepositoryAccess();
            }
            default -> throw new IllegalStateException(
                    "sdlc.repository.source must be 'github' or 'development'");
        };
    }
}
