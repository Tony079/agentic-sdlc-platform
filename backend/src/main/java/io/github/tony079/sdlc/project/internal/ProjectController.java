package io.github.tony079.sdlc.project.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.project.api.ProjectView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Projects of the caller's tenant. The tenant is taken from the session only, never from the request. */
@RestController
@RequestMapping("/api/v1/projects")
@ConditionalOnWebApplication
class ProjectController {

    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    ProjectList list() {
        return new ProjectList(projects.list());
    }

    @GetMapping("/{id}")
    ProjectView get(@PathVariable UUID id) {
        return projects.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectView create(@Valid @RequestBody CreateProjectRequest body) {
        return projects.create(body.name());
    }

    record CreateProjectRequest(@NotBlank @Size(max = 200) String name) {
    }

    record ProjectList(List<ProjectView> items) {
    }
}
