package io.github.tony079.sdlc.modelgateway.api;

/**
 * The only way platform code reaches a language model (PRD FR-LLM-01/02, TDD 13.1). Agents depend on this
 * interface, never on a provider. Each call: resolves the configured provider and enabled model; checks the prompt
 * is a registered, active version; checks budget caps; calls the provider with a per-attempt timeout and bounded
 * retries; parses and validates the structured output (one repair attempt); records provider, model, prompt
 * version, tokens, cost, latency, request and correlation ids; returns a normalised response or
 * {@link ModelException}.
 *
 * <p>Requires a tenant context and must be called outside a database transaction (model calls are slow; the usage
 * record is written in its own short transaction). The gateway has no access to workflow state, approvals, jobs,
 * storage or tools.
 */
public interface ModelGateway {

    <T> ModelResponse<T> generate(ModelRequest<T> request);
}
