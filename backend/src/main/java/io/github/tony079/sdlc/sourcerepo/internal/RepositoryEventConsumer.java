package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.outbox.api.DomainEvent;
import io.github.tony079.sdlc.outbox.api.EventConsumer;
import io.github.tony079.sdlc.outbox.api.EventTypes;
import io.github.tony079.sdlc.queue.api.JobQueue;
import io.github.tony079.sdlc.queue.api.JobRequest;

import org.springframework.stereotype.Component;

/**
 * Worker reactions of the repository stage: RepositoryRegistered enqueues REPO_VERIFY (the API cannot touch the job
 * queue); a dead REPO_VERIFY marks the repository UNAVAILABLE and wakes waiting validations; a dead REPO_VALIDATE
 * records the failure code on the requirement's repository (the workflow side, T09, is the engine's consumer).
 */
@Component
class RepositoryEventConsumer implements EventConsumer {

    private final JobQueue jobs;
    private final WorkerRepository repositories;

    RepositoryEventConsumer(JobQueue jobs, WorkerRepository repositories) {
        this.jobs = jobs;
        this.repositories = repositories;
    }

    @Override
    public String name() {
        return "sourcerepo-repository";
    }

    @Override
    public boolean accepts(String eventType) {
        return EventTypes.REPOSITORY_REGISTERED.equals(eventType) || EventTypes.JOB_DEAD.equals(eventType);
    }

    @Override
    public void handle(DomainEvent event) {
        if (EventTypes.REPOSITORY_REGISTERED.equals(event.eventType())) {
            UUID repositoryId = event.uuid("repositoryId");
            UUID requirementId = event.uuid("requirementId");
            jobs.enqueue(new JobRequest(RepoJobs.VERIFY, "repo-verify:" + repositoryId + ":" + requirementId,
                    RegistrationService.AGGREGATE, repositoryId, null,
                    Map.of("repositoryId", repositoryId.toString(), "requirementId", requirementId.toString()), 100));
            return;
        }
        Object jobType = event.payload().get("jobType");
        if (RepoJobs.VERIFY.equals(jobType)) {
            UUID repositoryId = event.aggregateId();
            repositories.repository(repositoryId, true).filter(r -> r.status().equals("PENDING_VERIFICATION"))
                    .ifPresent(r -> repositories.markRepository(repositoryId, "UNAVAILABLE", "VERIFY_FAILED"));
            for (UUID requirementId : repositories.pendingRequirements(repositoryId)) {
                jobs.makeAvailable(RepoJobs.validateKey(requirementId));
            }
        } else if (RepoJobs.VALIDATE.equals(jobType)) {
            String code = repositories.lastErrorCode(event.uuid("jobId")).orElse("REPO_VALIDATION_FAILED");
            repositories.failValidation(event.aggregateId(), code);
        }
    }
}
