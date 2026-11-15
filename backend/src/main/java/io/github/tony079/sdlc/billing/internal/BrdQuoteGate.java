package io.github.tony079.sdlc.billing.internal;

import java.math.BigDecimal;
import java.util.UUID;

import io.github.tony079.sdlc.billing.api.BrdQuotes;
import io.github.tony079.sdlc.tenancy.api.TenantContext;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs in the worker's tenant transaction when guard 9.4 is evaluated (GD-22). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class BrdQuoteGate implements BrdQuotes {

    private final JdbcClient jdbc;

    BrdQuoteGate(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void open(UUID requirementId, UUID projectId, Basis basis) {
        UUID planId = jdbc.sql("SELECT plan_id FROM app.project WHERE id = :p").param("p", projectId)
                .query(UUID.class).optional().orElse(null);
        if (planId == null) {
            return;
        }
        String size = BrdSizing.size(basis);
        Object[] price = price(planId, size);
        jdbc.sql("""
                INSERT INTO app.brd_quote (tenant_id, project_id, requirement_id, plan_id, currency, size, sizing_method,
                                           documents, has_repository, text_chars, list_price)
                VALUES (:t, :p, :r, :plan, :currency, :size, :method, :docs, :repo, :chars, :price)
                ON CONFLICT (requirement_id) DO NOTHING
                """).param("t", TenantContext.requireTenant()).param("p", projectId).param("r", requirementId)
                .param("plan", planId).param("currency", price[0]).param("size", size).param("method", BrdSizing.METHOD)
                .param("docs", basis.documents()).param("repo", basis.hasRepository()).param("chars", basis.textChars())
                .param("price", (BigDecimal) price[1]).update();
    }

    @Override
    public boolean readyForAnalysis(UUID requirementId, Basis basis) {
        Object[] quote = jdbc.sql("SELECT status, plan_id FROM app.brd_quote WHERE requirement_id = :r")
                .param("r", requirementId).query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)})
                .optional().orElse(null);
        if (quote == null || "ACCEPTED".equals(quote[0])) {
            return true;
        }
        if ("PROPOSED".equals(quote[0])) {
            // Inputs may have changed since submission (documents added), so quote from the current inputs.
            UUID planId = (UUID) quote[1];
            String size = BrdSizing.size(basis);
            Object[] price = price(planId, size);
            jdbc.sql("""
                    UPDATE app.brd_quote SET size = :size, documents = :docs, has_repository = :repo, text_chars = :chars,
                           list_price = :price, quoted_at = clock_timestamp()
                    WHERE requirement_id = :r AND status = 'PROPOSED'
                      AND (size, documents, has_repository, text_chars, list_price)
                          IS DISTINCT FROM (CAST(:size AS text), CAST(:docs AS int), CAST(:repo AS boolean),
                                            CAST(:chars AS int), CAST(:price AS numeric))
                    """).param("size", size).param("docs", basis.documents()).param("repo", basis.hasRepository())
                    .param("chars", basis.textChars()).param("price", (BigDecimal) price[1]).param("r", requirementId)
                    .update();
        }
        return false;
    }

    private Object[] price(UUID planId, String size) {
        return jdbc.sql("""
                SELECT currency, CASE :size WHEN 'SMALL' THEN price_small WHEN 'MEDIUM' THEN price_medium ELSE price_large END
                FROM app.billing_plan WHERE id = :plan
                """).param("size", size).param("plan", planId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getBigDecimal(2)}).single();
    }
}
