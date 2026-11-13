package io.github.tony079.sdlc.requirement.internal;

import java.util.Optional;
import java.util.UUID;

import io.github.tony079.sdlc.requirement.api.RequirementDirectory;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcRequirementDirectory implements RequirementDirectory {

    private final RequirementRepository requirements;

    private final io.github.tony079.sdlc.project.api.ProjectAccess projects;

    JdbcRequirementDirectory(RequirementRepository requirements, io.github.tony079.sdlc.project.api.ProjectAccess projects) {
        this.requirements = requirements;
        this.projects = projects;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<VisibleRequirement> findVisible(UUID requirementId) {
        UUID actor = TenantContext.currentActor().orElse(null);
        return requirements.findVisible(requirementId).map(r -> new VisibleRequirement(r.id(), r.projectId(),
                r.createdBy(), r.createdBy().equals(actor), projects.roleOf(r.projectId()).orElse(null)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OwnedRequirement> findOwned(UUID requirementId) {
        UUID actor = TenantContext.currentActor().orElseThrow(() -> new IllegalStateException("No authenticated actor"));
        return requirements.findOwned(requirementId, actor).map(r -> new OwnedRequirement(r.id(), r.projectId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> projectOf(UUID requirementId) {
        return requirements.projectOf(requirementId);
    }
}
