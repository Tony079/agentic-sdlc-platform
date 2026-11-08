package io.github.tony079.sdlc.requirement.internal;

import java.util.UUID;

import io.github.tony079.sdlc.common.api.ApiException;
import io.github.tony079.sdlc.requirement.api.RequirementView.Content;
import io.github.tony079.sdlc.requirement.api.RequirementView.Revision;
import io.github.tony079.sdlc.requirement.api.ScopeRevisions;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcScopeRevisions implements ScopeRevisions {

    private final RequirementRepository requirements;

    JdbcScopeRevisions(RequirementRepository requirements) {
        this.requirements = requirements;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Current current(UUID requirementId) {
        RequirementRepository.Row row = requirements.find(requirementId).orElseThrow(ApiException::notFound);
        Revision revision = requirements.findRevision(requirementId, row.currentRevisionNo()).orElseThrow();
        return new Current(row.currentRevisionNo(), revision.content(), revision.contentHash());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyAcceptedScope(UUID requirementId, int expectedRevisionNo, Content content, UUID submittedBy) {
        RequirementRepository.Row row = requirements.find(requirementId).orElseThrow(ApiException::notFound);
        if (row.currentRevisionNo() != expectedRevisionNo) {
            throw ApiException.conflict("STALE_REVISION", "The requirement changed since the change was proposed.");
        }
        Content current = requirements.findRevision(requirementId, row.currentRevisionNo()).orElseThrow().content();
        RequirementFields fields = RequirementFields.of(new Content(current.kind(), content.title(), content.description(),
                content.businessObjective(), content.expectedOutcomes(), content.acceptanceCriteria(),
                current.repositoryUrl(), content.constraints(), content.integrations()));
        if (!fields.submitErrors().isEmpty()) {
            throw ApiException.validation(fields.submitErrors());
        }
        int next = row.currentRevisionNo() + 1;
        if (!requirements.advance(requirementId, row.version(), next)) {
            throw ApiException.versionChanged();
        }
        requirements.insertRevision(TenantContext.requireTenant(), requirementId, next, fields, submittedBy);
        return next;
    }

    @Override
    public String hash(Content content) {
        return RequirementFields.of(content).contentHash();
    }
}
