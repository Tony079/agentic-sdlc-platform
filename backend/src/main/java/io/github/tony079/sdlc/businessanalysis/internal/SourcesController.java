package io.github.tony079.sdlc.businessanalysis.internal;

import java.util.UUID;

import io.github.tony079.sdlc.businessanalysis.internal.SourcesService.View;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** GD-23: the code a requirement was analysed against, moved branches, and re-analysis. */
@RestController
@ConditionalOnWebApplication
class SourcesController {

    private final SourcesService sources;

    SourcesController(SourcesService sources) {
        this.sources = sources;
    }

    @GetMapping("/api/v1/requirements/{id}/sources")
    View sources(@PathVariable UUID id) {
        return sources.view(id);
    }

    @PostMapping("/api/v1/requirements/{id}/reanalyse")
    View reanalyse(@PathVariable UUID id) {
        return sources.reanalyse(id);
    }
}
