--liquibase formatted sql

-- O-01 (owner, 2026-11-20): the operator registers the model named in .env (SDLC_MODEL_PROVIDER, SDLC_MODEL_ID) with
-- its prices, so a model can be switched without a code change and recorded AI cost (and the budget caps) stay
-- correct. Runs on every migration: nothing happens unless the provider is a hosted one and both prices are numbers;
-- an existing entry for the same model gets the new prices.

--changeset sdlc:0052-configured-model runAlways:true runOnChange:true splitStatements:false
-- rls: writes the model registry (not a tenant table).
DO $model$
BEGIN
  IF '${modelProvider}' IN ('azure-openai', 'openai', 'anthropic')
     AND '${modelId}' ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$'
     AND '${modelPriceInputPerMillion}' ~ '^[0-9]+(\.[0-9]+)?$'
     AND '${modelPriceOutputPerMillion}' ~ '^[0-9]+(\.[0-9]+)?$'
     AND '${modelMaxOutputTokens}' ~ '^[0-9]{1,6}$' THEN
    INSERT INTO app.model_registry (provider, model_id, data_class, max_output_tokens, input_price_per_1k,
                                    output_price_per_1k, status)
    VALUES ('${modelProvider}', '${modelId}', 'HOSTED', '${modelMaxOutputTokens}'::int,
            round('${modelPriceInputPerMillion}'::numeric / 1000, 6),
            round('${modelPriceOutputPerMillion}'::numeric / 1000, 6), 'ENABLED')
    ON CONFLICT (provider, model_id) DO UPDATE
      SET max_output_tokens = EXCLUDED.max_output_tokens, input_price_per_1k = EXCLUDED.input_price_per_1k,
          output_price_per_1k = EXCLUDED.output_price_per_1k, status = 'ENABLED';
  END IF;
END
$model$;
