package io.github.tony079.sdlc.modelgateway.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registry, prompt and usage-record access. Each method runs in the caller's (short) transaction. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ModelCallStore {

    record Model(int maxOutputTokens, BigDecimal inputPricePer1k, BigDecimal outputPricePer1k) {

        BigDecimal cost(int inputTokens, int outputTokens) {
            BigDecimal thousand = BigDecimal.valueOf(1000);
            return inputPricePer1k.multiply(BigDecimal.valueOf(inputTokens)).divide(thousand, 6, RoundingMode.HALF_UP)
                    .add(outputPricePer1k.multiply(BigDecimal.valueOf(outputTokens)).divide(thousand, 6, RoundingMode.HALF_UP));
        }
    }

    record Usage(UUID requestId, String correlationId, UUID tenantId, UUID requirementId, String provider, String modelId,
                 String promptId, int promptVersion, String outcome, int attempts, int inputTokens, int outputTokens,
                 BigDecimal costUsd, long latencyMs) {
    }

    private final JdbcClient jdbc;

    ModelCallStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An ENABLED model of the provider whose data class matches it (the fake provider only runs FAKE models). */
    Optional<Model> enabledModel(String provider, String modelId) {
        return jdbc.sql("""
                SELECT max_output_tokens, input_price_per_1k, output_price_per_1k FROM app.model_registry
                WHERE provider = :provider AND model_id = :model AND status = 'ENABLED'
                """)
                .param("provider", provider).param("model", modelId)
                .query((rs, n) -> new Model(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
                .optional();
    }

    boolean promptActive(String promptId, int version, String sha256) {
        return jdbc.sql("""
                SELECT count(*) FROM app.prompt_version
                WHERE prompt_id = :id AND version = :version AND text_sha256 = :sha AND status = 'ACTIVE'
                """)
                .param("id", promptId).param("version", version).param("sha", sha256)
                .query(Integer.class).single() == 1;
    }

    /** Cost recorded for this tenant in the current calendar month (UTC), under RLS. */
    BigDecimal tenantMonthCost() {
        return jdbc.sql("""
                SELECT coalesce(sum(cost_usd), 0) FROM app.model_call_record
                WHERE created_at >= date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                """).query(BigDecimal.class).single();
    }

    BigDecimal requirementCost(UUID requirementId) {
        return jdbc.sql("SELECT coalesce(sum(cost_usd), 0) FROM app.model_call_record WHERE requirement_id = :requirement")
                .param("requirement", requirementId).query(BigDecimal.class).single();
    }

    void record(Usage usage) {
        jdbc.sql("""
                INSERT INTO app.model_call_record (tenant_id, request_id, correlation_id, requirement_id, provider, model_id,
                    prompt_id, prompt_version, outcome, attempts, input_tokens, output_tokens, cost_usd, latency_ms)
                VALUES (:tenant, :request, :correlation, :requirement, :provider, :model, :prompt, :version, :outcome,
                        :attempts, :in, :out, :cost, :latency)
                """)
                .param("tenant", usage.tenantId()).param("request", usage.requestId())
                .param("correlation", usage.correlationId()).param("requirement", usage.requirementId())
                .param("provider", usage.provider()).param("model", usage.modelId())
                .param("prompt", usage.promptId()).param("version", usage.promptVersion())
                .param("outcome", usage.outcome()).param("attempts", usage.attempts())
                .param("in", usage.inputTokens()).param("out", usage.outputTokens())
                .param("cost", usage.costUsd()).param("latency", usage.latencyMs())
                .update();
    }
}
