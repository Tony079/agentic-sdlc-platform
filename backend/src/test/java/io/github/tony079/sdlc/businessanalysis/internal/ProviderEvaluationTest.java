package io.github.tony079.sdlc.businessanalysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import org.junit.jupiter.api.Test;

/** O-01 harness scoring with a SCRIPTED provider (TEST ONLY: no real provider, no benchmark result). */
class ProviderEvaluationTest {

    private static ModelProvider scripted(Deque<Object> answers) {
        return new ModelProvider() {
            @Override
            public String name() {
                return "scripted";
            }

            @Override
            public Result complete(Call call) throws Failure {
                Object next = answers.removeFirst();
                if (next instanceof Failure f) {
                    throw f;
                }
                return new Result((String) next, new Usage(1000, 200, false));
            }
        };
    }

    @Test
    void scoresCompletenessCitationsUnsupportedClaimsSchemaAndCost() throws Exception {
        ProviderEvaluation.Task task = ProviderEvaluation.dataset().get(0);
        assertThat(task.id()).isEqualTo("loan-repayment-metadata");
        String good = """
                {"items": [
                  {"kind": "FACT", "section": "FACTS", "statement": "Repayments are served under /loans.",
                   "evidence": [{"type": "REPOSITORY", "path": "src/main/java/com/acme/LoanController.java",
                     "startLine": 3, "endLine": 5, "documentVersionId": null, "chunk": null,
                     "quote": "@RequestMapping(\\"/loans\\")"}], "risk": null, "component": null},
                  {"kind": "FACT", "section": "FACTS", "statement": "A repayment has an amount.",
                   "evidence": [{"type": "REPOSITORY", "path": "src/main/java/com/acme/Repayment.java",
                     "startLine": 2, "endLine": 2, "documentVersionId": null, "chunk": null,
                     "quote": "BigDecimal amount"}], "risk": null, "component": null},
                  {"kind": "FACT", "section": "FACTS", "statement": "Repayments already store the payment channel.",
                   "evidence": [{"type": "REPOSITORY", "path": "src/main/java/com/acme/Repayment.java",
                     "startLine": 9, "endLine": 9, "documentVersionId": null, "chunk": null,
                     "quote": "channel"}], "risk": null, "component": null}
                 ], "unsupported": [], "clarifications": []}""";
        Deque<Object> answers = new ArrayDeque<>(List.of(good, "not json",
                new ModelProvider.Failure(ModelProvider.Failure.Kind.TIMEOUT)));
        ModelProvider provider = scripted(answers);
        var runs = List.of(ProviderEvaluation.runOnce(provider, "m", task), ProviderEvaluation.runOnce(provider, "m", task),
                ProviderEvaluation.runOnce(provider, "m", task));
        var first = runs.get(0);
        assertThat(first.schemaValid()).isTrue();
        assertThat(first.found()).isEqualTo(2);
        assertThat(first.citations()).isEqualTo(3);
        assertThat(first.verifiedCitations()).isEqualTo(2);
        // The invented "channel" fact has no verifiable citation and asserts what the inputs do not contain.
        assertThat(first.unsupportedFacts()).isEqualTo(1);
        assertThat(runs.get(1).schemaValid()).isFalse();
        assertThat(runs.get(2).failed()).isTrue();

        var summary = ProviderEvaluation.summarise("scripted", "m", runs, new BigDecimal("4"), new BigDecimal("20"));
        assertThat(summary.failureRate()).isEqualTo(0.333);
        assertThat(summary.schemaValidRate()).isEqualTo(0.5);
        assertThat(summary.citationAccuracy()).isEqualTo(0.667);
        assertThat(summary.unsupportedClaimRate()).isEqualTo(0.333);
        assertThat(summary.costUsd()).isEqualByComparingTo("0.016");
        assertThat(ProviderEvaluation.summarise("scripted", "m", runs, null, null).costUsd()).isNull();

        String report = ProviderEvaluation.markdown(List.of(summary,
                ProviderEvaluation.notExecuted("openai", "-", "no credential")), 1, "ba-benchmark-v1");
        assertThat(report).contains("NOT_EXECUTED: no credential").contains("does not select a provider")
                .doesNotContainIgnoringCase("winner");
    }
}
