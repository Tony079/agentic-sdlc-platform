package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.github.tony079.sdlc.modelgateway.api.DataBlock;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reproducible provider evaluation for owner decision O-01 (OpenAI vs Anthropic). Each task of the fixed dataset is
 * sent with the production Phase B prompt ({@link BaPrompts#PHASE_B}) and line-numbered files to each provider, the
 * same number of times; outputs are checked with the production schema. Metrics per provider:
 * <ul>
 *   <li>completeness: expected facts found / expected facts (BRD quality proxy);</li>
 *   <li>citation accuracy: citations whose path exists, line range is valid and quote appears in the cited lines;</li>
 *   <li>unsupported-claim rate: FACT items without a verified citation, plus FACTs asserting what the inputs do not
 *       contain (the task's unsupported keywords);</li>
 *   <li>structured-output consistency: schema-valid outputs / completed calls;</li>
 *   <li>failure rate, latency (p50/p95), tokens, and cost from operator-supplied prices.</li>
 * </ul>
 * The harness reports measurements only; it names no winner. Selection is an owner decision.
 */
final class ProviderEvaluation {

    record Task(String id, String requirement, Map<String, String> files, List<List<String>> expectedFacts,
                List<String> unsupportedKeywords) {
    }

    record Run(String taskId, boolean failed, String failure, boolean schemaValid, int expected, int found,
               int citations, int verifiedCitations, int facts, int unsupportedFacts, long latencyMs,
               Integer inputTokens, Integer outputTokens) {
    }

    record Summary(String provider, String model, String status, int runs, double failureRate, double schemaValidRate,
                   double completeness, double citationAccuracy, double unsupportedClaimRate, long latencyP50Ms,
                   long latencyP95Ms, long inputTokens, long outputTokens, BigDecimal costUsd, List<Run> details) {
    }

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private ProviderEvaluation() {
    }

    static List<Task> dataset() throws IOException {
        try (InputStream in = ProviderEvaluation.class.getResourceAsStream("/evaluation/ba-benchmark-v1.json")) {
            JsonNode root = new JsonMapper().readTree(in);
            List<Task> tasks = new ArrayList<>();
            for (JsonNode t : root.path("tasks")) {
                Map<String, String> files = new LinkedHashMap<>();
                t.path("files").properties().forEach(e -> files.put(e.getKey(), e.getValue().asString()));
                List<List<String>> facts = new ArrayList<>();
                t.path("expectedFacts").forEach(f -> facts.add(f.path("keywords").valueStream()
                        .map(JsonNode::asString).toList()));
                tasks.add(new Task(t.path("id").asString(), t.path("requirement").asString(), files, facts,
                        t.path("unsupportedKeywords").valueStream().map(JsonNode::asString).toList()));
            }
            return tasks;
        }
    }

    static List<DataBlock> blocks(Task task) {
        List<DataBlock> blocks = new ArrayList<>();
        blocks.add(new DataBlock("requirement", task.requirement()));
        task.files().forEach((path, content) -> {
            StringBuilder numbered = new StringBuilder();
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                numbered.append(i + 1).append("| ").append(lines[i]).append('\n');
            }
            blocks.add(new DataBlock("repository-file path=" + path + " (line-numbered)", numbered.toString()));
        });
        return blocks;
    }

    static Run runOnce(ModelProvider provider, String model, Task task) {
        ModelProvider.Call call = new ModelProvider.Call(UUID.randomUUID(), "o01-eval", model, BaPrompts.PHASE_B.id(),
                BaPrompts.PHASE_B.version(), BaPrompts.PHASE_B.instructions(), blocks(task), 8000,
                Duration.ofSeconds(180));
        long start = System.nanoTime();
        ModelProvider.Result result;
        try {
            result = provider.complete(call);
        } catch (ModelProvider.Failure f) {
            return new Run(task.id(), true, f.kind().name(), false, task.expectedFacts().size(), 0, 0, 0, 0, 0,
                    (System.nanoTime() - start) / 1_000_000, null, null);
        } catch (RuntimeException e) {
            return new Run(task.id(), true, "ADAPTER_ERROR", false, task.expectedFacts().size(), 0, 0, 0, 0, 0,
                    (System.nanoTime() - start) / 1_000_000, null, null);
        }
        long latency = (System.nanoTime() - start) / 1_000_000;
        Integer in = result.usage() == null ? null : result.usage().inputTokens();
        Integer out = result.usage() == null ? null : result.usage().outputTokens();
        return score(task, result.text(), latency, in, out);
    }

    static Run score(Task task, String text, long latency, Integer in, Integer out) {
        BaContracts.Proposal proposal = null;
        try {
            proposal = text == null ? null : STRICT.readValue(text, BaContracts.Proposal.class);
            if (proposal != null && !VALIDATOR.validate(proposal).isEmpty()) {
                proposal = null;
            }
        } catch (RuntimeException e) {
            proposal = null;
        }
        if (proposal == null) {
            return new Run(task.id(), false, null, false, task.expectedFacts().size(), 0, 0, 0, 0, 0, latency, in, out);
        }
        int found = 0;
        for (List<String> keywords : task.expectedFacts()) {
            boolean match = proposal.items().stream().anyMatch(i -> keywords.stream().allMatch(
                    k -> i.statement().toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))));
            found += match ? 1 : 0;
        }
        int citations = 0;
        int verified = 0;
        int facts = 0;
        int unsupported = 0;
        for (BaContracts.Item item : proposal.items()) {
            int itemVerified = 0;
            for (BaContracts.Evidence e : item.evidence()) {
                if (e.type() != BaContracts.EvidenceType.REPOSITORY) {
                    continue;
                }
                citations++;
                if (verifies(task, e)) {
                    verified++;
                    itemVerified++;
                }
            }
            if (item.kind() == BaContracts.Kind.FACT) {
                facts++;
                String statement = item.statement().toLowerCase(Locale.ROOT);
                boolean invented = task.unsupportedKeywords().stream()
                        .anyMatch(k -> statement.contains(k.toLowerCase(Locale.ROOT)));
                if (itemVerified == 0 || invented) {
                    unsupported++;
                }
            }
        }
        return new Run(task.id(), false, null, true, task.expectedFacts().size(), found, citations, verified, facts,
                unsupported, latency, in, out);
    }

    private static boolean verifies(Task task, BaContracts.Evidence e) {
        String content = task.files().get(e.path());
        if (content == null || e.startLine() == null || e.endLine() == null || e.endLine() < e.startLine()) {
            return false;
        }
        String[] lines = content.split("\n", -1);
        if (e.endLine() > lines.length) {
            return false;
        }
        String range = String.join("\n", java.util.Arrays.copyOfRange(lines, e.startLine() - 1, e.endLine()));
        return range.replaceAll("\\s+", " ").contains(e.quote().strip().replaceAll("\\s+", " "));
    }

    /** Aggregates runs; cost only when both prices (USD per 1M tokens) were supplied by the operator. */
    static Summary summarise(String provider, String model, List<Run> runs, BigDecimal inPrice, BigDecimal outPrice) {
        int n = runs.size();
        long failed = runs.stream().filter(Run::failed).count();
        List<Run> completed = runs.stream().filter(r -> !r.failed()).toList();
        long valid = completed.stream().filter(Run::schemaValid).count();
        int expected = runs.stream().mapToInt(Run::expected).sum();
        int found = runs.stream().mapToInt(Run::found).sum();
        int citations = runs.stream().mapToInt(Run::citations).sum();
        int verified = runs.stream().mapToInt(Run::verifiedCitations).sum();
        int facts = runs.stream().mapToInt(Run::facts).sum();
        int unsupported = runs.stream().mapToInt(Run::unsupportedFacts).sum();
        List<Long> latencies = completed.stream().map(Run::latencyMs).sorted().toList();
        long in = runs.stream().filter(r -> r.inputTokens() != null).mapToLong(Run::inputTokens).sum();
        long out = runs.stream().filter(r -> r.outputTokens() != null).mapToLong(Run::outputTokens).sum();
        BigDecimal cost = inPrice == null || outPrice == null ? null
                : inPrice.multiply(BigDecimal.valueOf(in)).add(outPrice.multiply(BigDecimal.valueOf(out)))
                        .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return new Summary(provider, model, "EXECUTED", n, ratio(failed, n), ratio(valid, completed.size()),
                ratio(found, expected), ratio(verified, citations), ratio(unsupported, facts),
                percentile(latencies, 50), percentile(latencies, 95), in, out, cost, runs);
    }

    static Summary notExecuted(String provider, String model, String reason) {
        return new Summary(provider, model, "NOT_EXECUTED: " + reason, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of());
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 0 : Math.round(1000.0 * a / b) / 1000.0;
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    static String markdown(List<Summary> summaries, int runsPerTask, String datasetVersion) {
        StringBuilder md = new StringBuilder("# Model Provider Evaluation (O-01)\n\n");
        md.append("Dataset `").append(datasetVersion).append("`, ").append(runsPerTask)
                .append(" run(s) per task and provider, production Phase B prompt `ba.phase-b@2`.\n\n");
        md.append("| Provider | Model | Status | Runs | Failure rate | Schema-valid | Completeness | Citation accuracy | "
                + "Unsupported-claim rate | Latency p50 / p95 (ms) | Tokens in / out | Cost (USD) |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Summary s : summaries) {
            md.append("| ").append(s.provider()).append(" | ").append(s.model()).append(" | ").append(s.status())
                    .append(" | ").append(s.runs()).append(" | ").append(s.failureRate()).append(" | ")
                    .append(s.schemaValidRate()).append(" | ").append(s.completeness()).append(" | ")
                    .append(s.citationAccuracy()).append(" | ").append(s.unsupportedClaimRate()).append(" | ")
                    .append(s.latencyP50Ms()).append(" / ").append(s.latencyP95Ms()).append(" | ")
                    .append(s.inputTokens()).append(" / ").append(s.outputTokens()).append(" | ")
                    .append(s.costUsd() == null ? "not computed (no prices given)" : s.costUsd().toPlainString())
                    .append(" |\n");
        }
        md.append("\nThis report contains measurements only. It does not select a provider; that is the owner's decision"
                + " (O-01). A provider marked NOT_EXECUTED was not evaluated and must not be compared.\n");
        return md.toString();
    }
}
