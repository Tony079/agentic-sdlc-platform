package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

class DraftWatcherTest {

    /** Records lines instead of writing them. */
    static final class Recording extends ActivityLog {
        final List<String> lines = new ArrayList<>();

        Recording() {
            super(null, new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {
                }

                @Override
                protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) {
                }

                @Override
                protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {
                }
            });
        }

        @Override
        void add(UUID requirementId, Stage stage, UUID runId, Kind kind, String message) {
            lines.add(kind + " " + message);
        }
    }

    @Test
    void showsEachFinishedStatementOnceWithItsSectionAndSurvivesARepair() {
        Recording log = new Recording();
        DraftWatcher watcher = io.github.tony079.sdlc.tenancy.api.TenantContext.callAs(UUID.randomUUID(),
                () -> DraftWatcher.brd(log, UUID.randomUUID(), UUID.randomUUID(), JsonMapper.builder().build()));
        String answer = """
                {"feasibility": {"verdict": "FEASIBLE", "reasons": ["ok"]}, "items": [
                 {"kind": "FACT", "section": "CURRENT_SYSTEM", "statement": "Loans are served at \\"/loans\\".", "evidence": []},
                 {"kind": "RECOMMENDATION", "section": "SCENARIOS", "statement": "A repayment without a channel is rejected.", "evidence": []}
                ], "unsupported": [], "clarifications": [{"question": "Which channels are allowed?", "blocking": true}]}""";
        for (int cut = 1; cut <= answer.length(); cut += 7) {
            watcher.accept(answer.substring(0, cut));
        }
        watcher.accept(answer);
        // A repair attempt starts again: lines already shown are not repeated.
        watcher.accept(answer.substring(0, 40));
        watcher.accept(answer);
        assertThat(log.lines).containsExactly(
                "DRAFT How the system works today: Loans are served at \"/loans\".",
                "DRAFT Scenarios: A repayment without a channel is rejected.",
                "DRAFT Question: Which channels are allowed?");
    }
}
