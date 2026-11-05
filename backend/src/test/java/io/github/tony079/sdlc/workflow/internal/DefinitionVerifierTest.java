package io.github.tony079.sdlc.workflow.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** WF-D01: the engine refuses a stored workflow definition that differs from its compiled transition table. */
class DefinitionVerifierTest {

    private static List<Map<String, String>> compiled() {
        List<Map<String, String>> rules = new ArrayList<>();
        TransitionTable.RULES.forEach(r -> rules.add(Map.of("transition", r.transition().name(),
                "from", r.from().name(), "to", r.to().name())));
        return rules;
    }

    @Test
    void acceptsTheMatchingDefinition() {
        assertThatCode(() -> DefinitionVerifier.verify(compiled())).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnUnknownStateOrTransition() {
        List<Map<String, String>> rules = compiled();
        rules.add(Map.of("transition", "T30_RETRY_REPOSITORY", "from", "FAILED", "to", "REPO_VALIDATING"));
        assertThatThrownBy(() -> DefinitionVerifier.verify(rules)).hasMessageContaining("unknown transition");

        List<Map<String, String>> futureStage = compiled();
        futureStage.add(Map.of("transition", "T01_SUBMIT", "from", "DRAFT", "to", "ESTIMATION"));
        assertThatThrownBy(() -> DefinitionVerifier.verify(futureStage)).hasMessageContaining("unknown");
    }

    @Test
    void rejectsAMissingOrExtraRule() {
        List<Map<String, String>> missing = compiled();
        missing.remove(0);
        assertThatThrownBy(() -> DefinitionVerifier.verify(missing)).hasMessageContaining("does not match");

        List<Map<String, String>> extra = compiled();
        extra.add(Map.of("transition", "T01_SUBMIT", "from", "SUBMITTED", "to", "CANCELLED"));
        assertThatThrownBy(() -> DefinitionVerifier.verify(extra)).hasMessageContaining("does not match");
    }
}
