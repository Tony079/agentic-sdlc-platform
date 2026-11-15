package io.github.tony079.sdlc.sourcerepo.internal;

import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.queue.api.ClaimedJob;
import io.github.tony079.sdlc.queue.api.JobFailure;
import io.github.tony079.sdlc.queue.api.JobHandler;
import io.github.tony079.sdlc.sourcerepo.internal.WorkerRepository.ValidationRow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ENV_PIN (GD-23): pins a later round of a requirement's environment sources (the target environment changed before
 * the quote was accepted, or the author re-analyses after branches moved). No workflow transition: analysis starts when
 * the new snapshots are indexed (guard 9.4). A newer round makes this one obsolete.
 */
@Component
@Profile("!migrate & !provision")
@ConditionalOnProperty(name = "sdlc.worker.enabled", havingValue = "true")
class EnvPinHandler implements JobHandler<EnvPinHandler.Outcome> {

    static final String JOB_TYPE = "ENV_PIN";

    record Outcome(int round, List<SourcePinner.Pin> pins) {
    }

    private final SourcePinner pinner;
    private final WorkerRepository repositories;
    private final TransactionTemplate tx;

    EnvPinHandler(SourcePinner pinner, WorkerRepository repositories, PlatformTransactionManager transactionManager) {
        this.pinner = pinner;
        this.repositories = repositories;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    static String key(UUID requirementId, int round) {
        return "env-pin:" + requirementId + ":" + round;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public Outcome execute(ClaimedJob job) throws JobFailure {
        UUID requirementId = RepoJobs.uuid(job, "requirementId");
        int round = Integer.parseInt(String.valueOf(job.payload().get("round")));
        List<ValidationRow> rows = tx.execute(s -> repositories.latestRound(requirementId));
        if (rows == null || rows.isEmpty() || rows.get(0).round() != round) {
            return new Outcome(round, List.of()); // superseded by a newer round
        }
        return new Outcome(round, pinner.resolve(rows));
    }

    @Override
    public void commit(ClaimedJob job, Outcome outcome) {
        List<ValidationRow> rows = repositories.latestRound(job.aggregateId());
        if (outcome.pins().isEmpty() || rows.isEmpty() || rows.get(0).round() != outcome.round()) {
            return;
        }
        pinner.commit(job.aggregateId(), outcome.pins());
    }
}
