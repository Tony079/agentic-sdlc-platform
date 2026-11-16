--liquibase formatted sql

-- O-01 (owner, 2026-11-16): the BA agent runs on GPT-4.1 mini in Azure OpenAI (Microsoft Foundry), Global Standard.
-- The model id is the Foundry deployment name; Foundry names a deployment after its model unless renamed. Prices per
-- 1,000 tokens from Azure's Global Standard rate at registration (USD 0.40 input / 1.60 output per million); update
-- them here if Azure changes its prices, so recorded AI cost stays correct.

--changeset sdlc:0039-azure-openai-model
INSERT INTO app.model_registry (provider, model_id, data_class, max_output_tokens, input_price_per_1k,
                                output_price_per_1k, status)
VALUES ('azure-openai', 'gpt-4.1-mini', 'HOSTED', 32768, 0.000400, 0.001600, 'ENABLED');
