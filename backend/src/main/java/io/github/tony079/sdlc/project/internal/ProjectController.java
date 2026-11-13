package io.github.tony079.sdlc.project.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.project.api.ProjectRole;
import io.github.tony079.sdlc.project.api.ProjectView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Projects and memberships of the caller's organisation. The tenant comes from the session only. */
@RestController
@ConditionalOnWebApplication
class ProjectController {

    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping("/api/v1/projects")
    ProjectList list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return new ProjectList(projects.list(includeArchived));
    }

    @GetMapping("/api/v1/projects/{id}")
    ProjectView get(@PathVariable UUID id) {
        return projects.get(id);
    }

    @PostMapping("/api/v1/projects")
    @ResponseStatus(HttpStatus.CREATED)
    ProjectView create(@Valid @RequestBody ProjectBody body) {
        return projects.create(body.name(), body.description());
    }

    @PutMapping("/api/v1/projects/{id}")
    ProjectView update(@PathVariable UUID id, @Valid @RequestBody ProjectBody body) {
        return projects.update(id, body.name(), body.description());
    }

    @PostMapping("/api/v1/projects/{id}/archive")
    ProjectView archive(@PathVariable UUID id) {
        return projects.archive(id);
    }

    @GetMapping("/api/v1/projects/{id}/members")
    List<ProjectRepository.Member> members(@PathVariable UUID id) {
        return projects.members(id);
    }

    @PostMapping("/api/v1/projects/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    ProjectRepository.Member addMember(@PathVariable UUID id, @Valid @RequestBody AddMemberBody body) {
        return projects.addProjectMember(id, body.userId(), body.role());
    }

    @PutMapping("/api/v1/project-members/{memberId}")
    ProjectRepository.Member changeRole(@PathVariable UUID memberId, @Valid @RequestBody RoleBody body) {
        return projects.changeRole(memberId, body.role());
    }

    @PostMapping("/api/v1/project-members/{memberId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID memberId) {
        projects.removeMember(memberId);
    }

    record ProjectBody(@Size(max = 200) String name, @Size(max = 2000) String description) {
    }

    record AddMemberBody(UUID userId, ProjectRole role) {
    }

    record RoleBody(ProjectRole role) {
    }

    record ProjectList(List<ProjectView> items) {
    }
}
