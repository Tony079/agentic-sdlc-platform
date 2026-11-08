# Model Provider Evaluation Report (O-01, 2026-11-08)

> **Result: NOT EXECUTED.** No provider credentials were available to this session. No provider is selected and **no benchmark result exists**. No number in this report was invented.

## 1. What exists

| Item | Location |
|---|---|
| Anthropic adapter (`/v1/messages`) | `modelgateway/internal/AnthropicModelProvider.java` |
| OpenAI adapter (`/v1/chat/completions`) | `modelgateway/internal/OpenAiModelProvider.java` |
| Provider factory (keys only from `SecretsProvider`) | `modelgateway/api/HostedProviders.java` |
| Evaluation logic | `businessanalysis/internal/ProviderEvaluation.java` |
| Command line | `businessanalysis/internal/ProviderEvaluationMain.java` (no database, no Spring context) |
| Dataset | `resources/evaluation/ba-benchmark-v1.json`: 3 tasks (`loan-repayment-metadata`, `customer-address-change`, `invoice-pdf-export`), each with requirement text, source files, expected facts and "unsupported" keywords |
| Prompt | The production Phase B prompt `ba.phase-b@2` |

## 2. Metrics

These are measured per provider, over N runs per task:

| Metric | Definition |
|---|---|
| Failure rate | Runs that ended in a gateway failure |
| Schema-valid rate | Runs whose output passed the strict BRD proposal schema |
| Completeness | Expected facts found / expected facts (a proxy for BRD quality) |
| Citation accuracy | Citations that pass the production evidence verifier / all citations |
| Unsupported-claim rate | FACT items with no verified citation, plus FACT items asserting something the inputs do not contain (the task's unsupported keywords) / FACT items |
| Latency | p50 and p95, milliseconds |
| Tokens | Input and output tokens as reported by the provider |
| Cost | Tokens × the operator-supplied price per million tokens. Without prices it reads "not computed" (no prices are hard-coded) |

## 3. How to run it

Prerequisites:
- a key for each provider, as a secret (`SDLC_SECRET_LLM_ANTHROPIC_KEY`, `SDLC_SECRET_LLM_OPENAI_KEY`, or files under `/run/secrets`);
- egress to `api.anthropic.com` and `api.openai.com`;
- model ids chosen by the owner.

```bash
java -cp backend/target/sdlc-platform-0.1.0-SNAPSHOT.jar \
  -Dloader.main=io.github.tony079.sdlc.businessanalysis.internal.ProviderEvaluationMain \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --anthropic-model=MODEL --openai-model=MODEL --runs=3 --output=eval-out
```

Optional flags: `--anthropic-price-in`, `--anthropic-price-out`, `--openai-price-in`, `--openai-price-out` (USD per million tokens). The command writes `report.md` and `results.json`. A provider without a key or model id is reported NOT_EXECUTED and must not be compared.

## 4. Run on 2026-11-08

The command ran from the packaged jar with no keys. Its output:

| Provider | Model | Status | Runs |
|---|---|---|---|
| anthropic | claude-opus-5-5 | NOT_EXECUTED: no credential | 0 |
| openai | configured-by-owner | NOT_EXECUTED: no credential | 0 |

All metric columns are zero because nothing ran. The zeros are not measurements.

## 5. Limits of the method

- Three tasks are too few for a selection decision. They suit a smoke comparison; the owner should add representative tasks before choosing.
- Completeness and unsupported-claim detection use keywords, a lexical proxy. Human review of sample BRDs is still needed.
- Contract terms, data processing, data retention and region are not measured here. They are part of the O-01 decision.

## 6. Decision status

O-01 approves the adapters and the benchmark, **not** a winner. The default provider stays `none`. Selecting a provider (`SDLC_MODEL_PROVIDER`, `SDLC_MODEL_ID`) is the owner's decision after a real run.
