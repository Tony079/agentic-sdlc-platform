package io.github.tony079.sdlc.operations.internal;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells signed-in users which adapters this deployment is configured with, by name only, so the user interface can
 * state plainly when model output comes from the development mock or repositories from the bundled sample. It
 * reports configuration names and nothing else: no keys, hosts or tenant data.
 */
@RestController
@Profile("!migrate & !provision & !retention")
class RuntimeInfoController {

    record RuntimeInfo(String modelProvider, String repositorySource, List<String> developmentAdapters) {
    }

    private final RuntimeInfo info;

    RuntimeInfoController(@Value("${sdlc.model.provider:none}") String modelProvider,
                          @Value("${sdlc.repository.source:github}") String repositorySource) {
        List<String> development = new ArrayList<>();
        String provider = modelProvider.strip();
        String source = repositorySource.strip();
        if ("development-mock".equals(provider) || "fake".equals(provider)) {
            development.add("MODEL");
        }
        if ("development".equals(source)) {
            development.add("REPOSITORY");
        }
        this.info = new RuntimeInfo(provider, source, List.copyOf(development));
    }

    @GetMapping("/api/v1/runtime")
    RuntimeInfo runtime() {
        return info;
    }
}
