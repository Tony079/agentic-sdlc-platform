package io.github.tony079.sdlc.businessanalysis.internal;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.tony079.sdlc.common.api.SecretsProvider;
import io.github.tony079.sdlc.modelgateway.api.HostedProviders;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider;

import tools.jackson.databind.json.JsonMapper;

/**
 * Command line for the O-01 provider evaluation. No database, no Spring context. Example:
 * <pre>
 * SDLC_SECRET_LLM_ANTHROPIC_KEY=... SDLC_SECRET_LLM_OPENAI_KEY=... \
 * java -cp app.jar -Dloader.main=io.github.tony079.sdlc.businessanalysis.internal.ProviderEvaluationMain \
 *   org.springframework.boot.loader.launch.PropertiesLauncher \
 *   --anthropic-model=MODEL --openai-model=MODEL --runs=3 --output=eval-out \
 *   [--anthropic-price-in=USD_PER_MTOK --anthropic-price-out=...] [--openai-price-in=... --openai-price-out=...]
 * </pre>
 * A provider without a key or model is reported NOT_EXECUTED. Keys are read from the environment only and never
 * printed. Prices are operator input (current list prices), never defaults.
 */
public final class ProviderEvaluationMain {

    private ProviderEvaluationMain() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                options.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
            }
        }
        SecretsProvider secrets = new SecretsProvider() {
            @Override
            public String provider() {
                return "env";
            }

            @Override
            public Optional<String> secret(String name) {
                String value = System.getenv("SDLC_SECRET_" + name.toUpperCase(Locale.ROOT).replaceAll("[.-]", "_"));
                return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        };
        Path output = Path.of(options.getOrDefault("output", "provider-evaluation"));
        int runs = Integer.parseInt(options.getOrDefault("runs", "3"));
        List<ProviderEvaluation.Task> tasks = ProviderEvaluation.dataset();
        List<ProviderEvaluation.Summary> summaries = new ArrayList<>();
        for (String provider : List.of("anthropic", "openai")) {
            String model = options.get(provider + "-model");
            String keyName = "llm." + provider + "-key";
            if (model == null || model.isBlank()) {
                summaries.add(ProviderEvaluation.notExecuted(provider, "-", "no model given"));
                continue;
            }
            if (secrets.secret(keyName).isEmpty()) {
                summaries.add(ProviderEvaluation.notExecuted(provider, model, "no credential"));
                continue;
            }
            ModelProvider adapter = HostedProviders.create(provider, secrets, options.get(provider + "-base-url"), false);
            List<ProviderEvaluation.Run> results = new ArrayList<>();
            for (ProviderEvaluation.Task task : tasks) {
                for (int i = 0; i < runs; i++) {
                    results.add(ProviderEvaluation.runOnce(adapter, model, task));
                }
            }
            summaries.add(ProviderEvaluation.summarise(provider, model, results,
                    price(options.get(provider + "-price-in")), price(options.get(provider + "-price-out"))));
        }
        Files.createDirectories(output);
        Files.writeString(output.resolve("results.json"), new JsonMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(summaries));
        Files.writeString(output.resolve("report.md"), ProviderEvaluation.markdown(summaries, runs, "ba-benchmark-v1"));
        System.out.println("Evaluation written to " + output.toAbsolutePath());
    }

    private static BigDecimal price(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }
}
