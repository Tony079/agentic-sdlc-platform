package io.github.tony079.sdlc.modelgateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider.Call;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider.Result;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The development mock: deterministic, visibly marked, quoting only text that is really in its inputs. */
class DevelopmentMockModelProviderTest {

    private static final String REQUIREMENT = """
            Title: Refund reasons
            Description: Support staff must record a reason when they refund an order.
            Acceptance criteria: A refund without a reason is rejected.
            """;
    private static final String FILES = """
            README.md\t-\t120
            src/main/java/com/example/orders/RefundPolicy.java\tjava\t400
            src/main/java/com/example/orders/Order.java\tjava\t700
            """;
    private static final String REFUND_FILE = """
            1| package com.example.orders;
            2|\s
            3| public class RefundPolicy {
            4|     static final int REFUND_WINDOW_DAYS = 14;
            5| }
            """;

    private final DevelopmentMockModelProvider provider = new DevelopmentMockModelProvider();
    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode run(String promptId, List<DataBlock> data) {
        Result result = provider.complete(new Call(UUID.randomUUID(), "corr", DevelopmentMockModelProvider.MODEL_ID,
                promptId, 1, "instructions", data, 4096, Duration.ofSeconds(5)));
        assertThat(result.usage().estimated()).isTrue();
        return json.readTree(result.text());
    }

    private List<DataBlock> phaseBData() {
        List<DataBlock> data = new ArrayList<>();
        data.add(new DataBlock("requirement", REQUIREMENT));
        data.add(new DataBlock("repository-file path=src/main/java/com/example/orders/RefundPolicy.java (line-numbered)",
                REFUND_FILE));
        data.add(new DataBlock("document documentVersionId=11111111-1111-1111-1111-111111111111 chunk=2 file=notes.txt"
                + " section=Intro", "Refunds need a reason\nfrom next quarter."));
        return data;
    }

    @Test
    void phaseAChoosesRealPathsByKeywordAndSaysItIsAMock() {
        JsonNode out = run("ba.phase-a", List.of(new DataBlock("requirement", REQUIREMENT),
                new DataBlock("repository:files (path, language, bytes)", FILES)));

        assertThat(out.path("understanding").asString()).startsWith(DevelopmentMockModelProvider.MARKER)
                .contains("Refund reasons");
        // Source files only, the keyword match first; nothing that is not in the file list.
        assertThat(out.path("relevantPaths").valueStream().map(JsonNode::asString)).containsExactly(
                "src/main/java/com/example/orders/RefundPolicy.java", "src/main/java/com/example/orders/Order.java");
        assertThat(out.path("components").path(0).path("name").asString()).isEqualTo("RefundPolicy");
    }

    @Test
    void phaseBQuotesExactInputTextMarksEveryStatementAndAsksOneBlockingQuestion() {
        JsonNode out = run("ba.phase-b", phaseBData());

        assertThat(out.path("items").valueStream().map(i -> i.path("statement").asString()))
                .isNotEmpty().allMatch(s -> s.startsWith(DevelopmentMockModelProvider.MARKER));
        JsonNode fileFact = out.path("items").path(0);
        assertThat(fileFact.path("kind").asString()).isEqualTo("FACT");
        assertThat(fileFact.path("evidence").path(0).path("path").asString()).endsWith("RefundPolicy.java");
        // The keyword line is quoted exactly, with its real line number.
        assertThat(fileFact.path("evidence").path(0).path("startLine").asInt()).isEqualTo(3);
        assertThat(fileFact.path("evidence").path(0).path("quote").asString()).isEqualTo("public class RefundPolicy {");
        JsonNode documentFact = out.path("items").path(1);
        assertThat(documentFact.path("evidence").path(0).path("documentVersionId").asString())
                .isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(documentFact.path("evidence").path(0).path("chunk").asInt()).isEqualTo(2);
        assertThat(documentFact.path("evidence").path(0).path("quote").asString())
                .isEqualTo("Refunds need a reason from next quarter.");
        assertThat(out.path("unsupported").path(0).path("requirement").asString()).contains("without a reason");
        assertThat(out.path("clarifications")).hasSize(1);
        assertThat(out.path("clarifications").path(0).path("blocking").asBoolean()).isTrue();
        assertThat(out.path("clarifications").path(0).path("question").asString())
                .startsWith(DevelopmentMockModelProvider.MARKER);
        // Deterministic: the same inputs give the same output.
        assertThat(run("ba.phase-b", phaseBData())).isEqualTo(out);
    }

    @Test
    void anAnsweredClarificationIsUsedAndNotAskedAgain() {
        List<DataBlock> data = phaseBData();
        data.add(new DataBlock("reviewer correction notes (item key, instruction)", """
                [{"source":"CLARIFICATION","clarificationId":"c1","question":"q","answer":"Support staff only."},
                 {"itemKey":"FACTS-1","instruction":"Mention the 14 day window.","authorStaffId":"s1"}]
                """));
        JsonNode out = run("ba.phase-b", data);

        assertThat(out.path("clarifications")).isEmpty();
        assertThat(out.path("items").valueStream().map(i -> i.path("statement").asString()))
                .anyMatch(s -> s.contains("Support staff only."))
                .anyMatch(s -> s.contains("Mention the 14 day window."));
    }

    @Test
    void unknownPromptsGetAnEmptyObject() {
        assertThat(run("something.else", List.of()).isEmpty()).isTrue();
    }
}
