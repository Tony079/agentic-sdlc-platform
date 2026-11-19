package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.document.api.DocumentProcessing;
import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.ValidationRow;
import io.github.tony079.sdlc.workflow.api.WorkflowEngine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REPO_VALIDATE (enqueued by T02): waits (deferral, no retry budget) until the requirement's sources are registered
 * (a legacy linked repository, or the target environment's repositories, GD-23) and verified, then pins each head SHA
 * (branch head, or default-branch head for a linked repository) and performs T06, or T07 when an attachment needs a
 * decision.
 * Q-B Option A: never T08, never ANALYSING, never a BA job. A repository without access fails the job (REPO_ACCESS);
 * the dead job leads to T09.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class RepoValidateHandler implements JobHandler<RepoValidateHandler.Outcome> {

    record Outcome(List<SourcePinner.Pin> pins) {
    }

    private final SourcePinner pinner;
    private final WorkerRepository repositories;
    private final WorkflowEngine workflow;
    private final DocumentProcessing documents;
    private final TransactionTemplate tx;

    RepoValidateHandler(SourcePinner pinner, WorkerRepository repositories, WorkflowEngine workflow,
                        DocumentProcessing documents, PlatformTransactionManager transactionManager) {
        this.pinner = pinner;
        this.repositories = repositories;
        this.workflow = workflow;
        this.documents = documents;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    @Override
    public String jobType() {
        return RepoJobs.VALIDATE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID requirementId = RepoJobs.uuid(job, "requirementId");
        List<ValidationRow> round = tx.execute(status -> repositories.latestRound(requirementId));
        if (Boolean.TRUE.equals(tx.execute(status -> repositories.isNewProduct(requirementId)))) {
            return new Outcome(List.of()); // GD-28: nothing to pin; T06 follows and the analysis reads documents only
        }
        if (round == null || round.isEmpty()) {
            throw JobFailure.dependencyUnavailable("REPOSITORY_NOT_REGISTERED", RepoJobs.NOT_READY_DELAY);
        }
        return new Outcome(pinner.resolve(round));
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        UUID requirementId = job.aggregateId();
        boolean decision = documents.needsDecision(requirementId);
        if (workflow.completeRepositoryValidation(requirementId, job.stageExecutionId(), decision).isEmpty()) {
            return; // no longer REPO_VALIDATING (for example cancelled): nothing is pinned
        }
        pinner.commit(requirementId, outcome.pins());
    }
}
