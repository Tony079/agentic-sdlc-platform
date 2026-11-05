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

    JdbcRequirementDirectory(RequirementRepository requirements) {
        this.requirements = requirements;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OwnedRequirement> findOwned(UUID requirementId) {
        UUID actor = TenantContext.currentActor().orElseThrow(() -> new IllegalStateException("No authenticated actor"));
        return requirements.findOwned(requirementId, actor).map(r -> new OwnedRequirement(r.id(), r.projectId()));
    }
}
