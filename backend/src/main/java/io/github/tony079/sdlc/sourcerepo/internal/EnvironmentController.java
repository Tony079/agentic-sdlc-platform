package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.sourcerepo.internal.EnvironmentService.Overview;
import io.github.tony079.sdlc.sourcerepo.internal.EnvironmentService.Repository;
import io.github.tony079.sdlc.sourcerepo.internal.EnvironmentService.Result;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Project environments, repositories and branches (GD-20, GD-21). */
@RestController
@ConditionalOnWebApplication
class EnvironmentController {

    record ConnectBody(String repository) {
    }

    record EnvironmentBody(String name, String description, String reason) {
    }

    record BranchBody(UUID repositoryId, String branch, String reason) {
    }

    record ReasonBody(String reason) {
    }

    record TargetBody(UUID environmentId) {
    }

    private final EnvironmentService environments;

    EnvironmentController(EnvironmentService environments) {
        this.environments = environments;
    }

    @GetMapping("/api/v1/projects/{id}/environments")
    Overview overview(@PathVariable UUID id) {
        return environments.overview(id);
    }

    @PostMapping("/api/v1/projects/{id}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    Result create(@PathVariable UUID id, @RequestBody EnvironmentBody body) {
        return environments.createEnvironment(id, body.name(), body.description());
    }

    @PostMapping("/api/v1/projects/{id}/connected-repositories")
    @ResponseStatus(HttpStatus.CREATED)
    Repository connect(@PathVariable UUID id, @RequestBody ConnectBody body) {
        return environments.connect(id, body.repository());
    }

    @PostMapping("/api/v1/projects/{id}/environments/verify")
    ResponseEntity<Void> verify(@PathVariable UUID id) {
        environments.reverify(id);
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/api/v1/environments/{id}")
    Result rename(@PathVariable UUID id, @RequestBody EnvironmentBody body) {
        return environments.renameEnvironment(id, body.name(), body.description(), body.reason());
    }

    @DeleteMapping("/api/v1/environments/{id}")
    Result remove(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        return environments.removeEnvironment(id, reason);
    }

    @PostMapping("/api/v1/environments/{id}/mark-production")
    Result markProduction(@PathVariable UUID id, @RequestBody ReasonBody body) {
        return environments.markProduction(id, body.reason());
    }

    @PostMapping("/api/v1/environments/{id}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    Result addBranch(@PathVariable UUID id, @RequestBody BranchBody body) {
        return environments.addBranch(id, body.repositoryId(), body.branch(), body.reason());
    }

    @PutMapping("/api/v1/environment-branches/{id}")
    Result changeBranch(@PathVariable UUID id, @RequestBody BranchBody body) {
        return environments.changeBranch(id, body.branch(), body.reason());
    }

    @DeleteMapping("/api/v1/environment-branches/{id}")
    Result removeBranch(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        return environments.removeBranch(id, reason);
    }

    @PostMapping("/api/v1/environment-requests/{id}/{action}")
    Result decide(@PathVariable UUID id, @PathVariable String action) {
        return environments.decide(id, action);
    }

    @GetMapping("/api/v1/requirements/{id}/target-environment")
    Map<String, Object> target(@PathVariable UUID id) {
        return environments.targetEnvironment(id);
    }

    @PutMapping("/api/v1/requirements/{id}/delivery-environment")
    ResponseEntity<Void> delivery(@PathVariable UUID id, @RequestBody TargetBody body) {
        environments.setDeliveryEnvironment(id, body.environmentId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/requirements/{id}/target-environment")
    ResponseEntity<Void> target(@PathVariable UUID id, @RequestBody TargetBody body) {
        environments.setTargetEnvironment(id, body.environmentId());
        return ResponseEntity.noContent().build();
    }
}
