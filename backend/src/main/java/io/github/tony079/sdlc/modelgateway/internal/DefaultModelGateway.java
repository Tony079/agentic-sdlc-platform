package io.github.tony079.sdlc.modelgateway.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.tony079.sdlc.common.api.CorrelationId;
import io.github.tony079.sdlc.modelgateway.api.ModelException;
import io.github.tony079.sdlc.modelgateway.api.ModelException.Code;
import io.github.tony079.sdlc.modelgateway.api.ModelGateway;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider;
import io.github.tony079.sdlc.modelgateway.api.ModelProvider.Failure;
import io.github.tony079.sdlc.modelgateway.api.ModelRequest;
import io.github.tony079.sdlc.modelgateway.api.ModelResponse;
import io.github.tony079.sdlc.modelgateway.api.ModelUsage;
import io.github.tony079.sdlc.tenancy.api.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model gateway (TDD 13): provider-neutral, structured, recorded. See {@link ModelGateway} for the contract.
 *
 * <p>Retry policy (TDD 13.4/13.5): timeouts and unavailability are retried inside the call up to
 * {@code sdlc.model.max-transient-retries} times with a fixed backoff, then reported as TIMEOUT or
 * PROVIDER_UNAVAILABLE for the job to defer; a rate limit is reported at once as RATE_LIMITED (defer without using the
 * job's retry budget); output that does not parse or validate gets exactly one repair attempt, then INVALID_OUTPUT.
 * The per-attempt timeout is enforced here, even if a provider ignores it.
 */
@Component
@Profile("!migrate & !provision")
class DefaultModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelGateway.class);
    /** Trusted, fixed text added on the single repair attempt. It never quotes the previous (untrusted) output. */
    static final String REPAIR_NOTE = "\n\nYour previous answer was not valid JSON for the required schema. "
            + "Answer again with only one JSON object that matches the schema exactly.";

    private final ModelProviderRegistry registry;
    private final ModelCallStore store;
    private final Validator validator;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final JsonMapper strictJson;
    private final ThreadPoolExecutor calls;
    private final Duration defaultTimeout;
    private final int maxTransientRetries;
    private final Duration retryBackoff;
    private final BigDecimal tenantMonthlyCap;
    private final BigDecimal requirementCap;

    DefaultModelGateway(ModelProviderRegistry registry, ModelCallStore store, Validator validator, MeterRegistry meters,
                        PlatformTransactionManager transactionManager,
                        @Value("${sdlc.model.timeout:120s}") Duration defaultTimeout,
                        @Value("${sdlc.model.max-transient-retries:2}") int maxTransientRetries,
                        @Value("${sdlc.model.retry-backoff:2s}") Duration retryBackoff,
                        @Value("${sdlc.model.budget.tenant-monthly-usd:}") String tenantMonthlyCap,
                        @Value("${sdlc.model.budget.requirement-usd:}") String requirementCap,
                        @Value("${sdlc.model.max-concurrent-calls:16}") int maxConcurrentCalls) {
        this.registry = registry;
        this.store = store;
        this.validator = validator;
        this.meters = meters;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.strictJson = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .build();
        // Bounded: a provider that ignores interruption after a timeout cannot make the gateway grow without limit.
        // When every slot is busy a call is refused as PROVIDER_UNAVAILABLE (a transient failure for job deferral).
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 256) {
            throw new IllegalStateException("sdlc.model.max-concurrent-calls must be between 1 and 256");
        }
        this.calls = new ThreadPoolExecutor(0, maxConcurrentCalls, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().name("sdlc-model-call-", 0).daemon().factory(), new ThreadPoolExecutor.AbortPolicy());
        this.defaultTimeout = defaultTimeout;
        if (maxTransientRetries < 0 || maxTransientRetries > 5) {
            throw new IllegalStateException("sdlc.model.max-transient-retries must be between 0 and 5");
        }
        this.maxTransientRetries = maxTransientRetries;
        this.retryBackoff = retryBackoff;
        this.tenantMonthlyCap = tenantMonthlyCap.isBlank() ? null : new BigDecimal(tenantMonthlyCap.strip());
        this.requirementCap = requirementCap.isBlank() ? null : new BigDecimal(requirementCap.strip());
    }

    @Override
    public <T> ModelResponse<T> generate(ModelRequest<T> request) {
        UUID tenant = TenantContext.currentTenant()
                .orElseThrow(() -> new IllegalStateException("Model calls require a tenant context"));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Model calls must not run inside a database transaction");
        }
        UUID requestId = UUID.randomUUID();
        String correlationId = CorrelationId.current().orElse(requestId.toString());
        ModelProvider provider = registry.provider()
                .orElseThrow(() -> configuration(requestId, "no model provider configured (O-01)"));
        String modelId = registry.modelId();
        ModelCallStore.Model model = inTx(() -> store.enabledModel(provider.name(), modelId))
                .orElseThrow(() -> configuration(requestId, "model not registered or not enabled"));
        String promptSha = request.prompt().sha256();
        if (!Boolean.TRUE.equals(tx.execute(s -> store.promptActive(request.prompt().id(), request.prompt().version(), promptSha)))) {
            throw configuration(requestId, "prompt version not registered or not active");
        }

        Attempts attempts = new Attempts(provider.name(), modelId, request, requestId, correlationId, tenant, model);
        if (budgetExceeded(request)) {
            attempts.finish("BUDGET_EXCEEDED");
            throw new ModelException(Code.BUDGET_EXCEEDED, requestId, null);
        }
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        int maxTokens = Math.min(request.maxOutputTokens(), model.maxOutputTokens());
        int transientLeft = maxTransientRetries;
        boolean repaired = false;
        while (true) {
            String instructions = request.prompt().instructions() + (repaired ? REPAIR_NOTE : "");
            ModelProvider.Call call = new ModelProvider.Call(requestId, correlationId, modelId, request.prompt().id(),
                    request.prompt().version(), instructions, request.data(), maxTokens, timeout);
            ModelProvider.Result result;
            try {
                attempts.count++;
                result = callWithTimeout(provider, call, timeout);
            } catch (Failure failure) {
                Code code = switch (failure.kind()) {
                    case TIMEOUT -> Code.TIMEOUT;
                    case UNAVAILABLE -> Code.PROVIDER_UNAVAILABLE;
                    case RATE_LIMITED -> Code.RATE_LIMITED;
                    case ERROR -> Code.PROVIDER_ERROR;
                };
                if ((code == Code.TIMEOUT || code == Code.PROVIDER_UNAVAILABLE) && transientLeft > 0) {
                    transientLeft--;
                    sleep(retryBackoff);
                    continue;
                }
                attempts.finish(code.name());
                throw new ModelException(code, requestId, null);
            }
            attempts.add(result.usage());
            if (result.text() == null || result.text().isBlank()) {
                if (!repaired) {
                    repaired = true;
                    continue;
                }
                attempts.finish("EMPTY_OUTPUT");
                throw new ModelException(Code.EMPTY_OUTPUT, requestId, null);
            }
            Optional<T> output = parse(result.text(), request.outputType());
            if (output.isEmpty()) {
                if (!repaired) {
                    repaired = true;
                    continue;
                }
                attempts.finish("INVALID_OUTPUT");
                throw new ModelException(Code.INVALID_OUTPUT, requestId, null);
            }
            ModelUsage usage = attempts.finish("SUCCEEDED");
            return new ModelResponse<>(output.get(), requestId, correlationId, provider.name(), modelId,
                    request.prompt().id(), request.prompt().version(), usage);
        }
    }

    /** Strict JSON binding (unknown, missing or trailing content rejected) plus Bean Validation of the result. */
    <T> Optional<T> parse(String text, Class<T> type) {
        try {
            T value = strictJson.readValue(text, type);
            if (value == null) {
                return Optional.empty();
            }
            Set<ConstraintViolation<T>> violations = validator.validate(value);
            return violations.isEmpty() ? Optional.of(value) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private ModelProvider.Result callWithTimeout(ModelProvider provider, ModelProvider.Call call, Duration timeout)
            throws Failure {
        Future<ModelProvider.Result> future;
        try {
            future = calls.submit(() -> provider.complete(call));
        } catch (RejectedExecutionException e) {
            log.warn("Model call refused: all {} call slots busy (request {})", calls.getMaximumPoolSize(), call.requestId());
            throw new Failure(Failure.Kind.UNAVAILABLE);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Failure(Failure.Kind.TIMEOUT);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Failure failure) {
                throw failure;
            }
            log.warn("Model provider {} failed unexpectedly (request {})", provider.name(), call.requestId());
            throw new Failure(Failure.Kind.ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new Failure(Failure.Kind.TIMEOUT);
        }
    }

    private boolean budgetExceeded(ModelRequest<?> request) {
        if (tenantMonthlyCap != null && inTx(store::tenantMonthCost).compareTo(tenantMonthlyCap) >= 0) {
            return true;
        }
        return requirementCap != null && request.requirementId() != null
                && inTx(() -> store.requirementCost(request.requirementId())).compareTo(requirementCap) >= 0;
    }

    /** Usage of one gateway call; recorded once in its own transaction whatever the outcome. */
    private final class Attempts {
        final String provider;
        final String modelId;
        final ModelRequest<?> request;
        final UUID requestId;
        final String correlationId;
        final UUID tenant;
        final ModelCallStore.Model model;
        final long started = System.nanoTime();
        int count;
        int inputTokens;
        int outputTokens;
        boolean anyResult;
        boolean anyEstimated;
        boolean anyWithoutUsage;

        Attempts(String provider, String modelId, ModelRequest<?> request, UUID requestId, String correlationId,
                 UUID tenant, ModelCallStore.Model model) {
            this.provider = provider;
            this.modelId = modelId;
            this.request = request;
            this.requestId = requestId;
            this.correlationId = correlationId;
            this.tenant = tenant;
            this.model = model;
        }

        void add(ModelProvider.Usage usage) {
            anyResult = true;
            if (usage == null) {
                anyWithoutUsage = true;
                return;
            }
            anyEstimated |= usage.estimated();
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
        }

        ModelUsage.Source source() {
            if (!anyResult) {
                return ModelUsage.Source.NONE;
            }
            if (anyWithoutUsage) {
                return ModelUsage.Source.UNAVAILABLE;
            }
            return anyEstimated ? ModelUsage.Source.ESTIMATED : ModelUsage.Source.REPORTED;
        }

        ModelUsage finish(String outcome) {
            Duration latency = Duration.ofNanos(System.nanoTime() - started);
            ModelUsage.Source source = source();
            boolean known = source == ModelUsage.Source.REPORTED || source == ModelUsage.Source.ESTIMATED;
            Integer in = known ? inputTokens : null;
            Integer out = known ? outputTokens : null;
            BigDecimal cost = known ? model.cost(inputTokens, outputTokens) : null;
            tx.executeWithoutResult(s -> store.record(new ModelCallStore.Usage(requestId, correlationId, tenant,
                    request.requirementId(), provider, modelId, request.prompt().id(), request.prompt().version(), outcome,
                    count, source.name(), in, out, cost, latency.toMillis())));
            meters.timer("sdlc.model.calls", "provider", provider, "model", modelId, "outcome", outcome).record(latency);
            if (known) {
                meters.counter("sdlc.model.tokens", "provider", provider, "direction", "input", "source", source.name())
                        .increment(inputTokens);
                meters.counter("sdlc.model.tokens", "provider", provider, "direction", "output", "source", source.name())
                        .increment(outputTokens);
            }
            // Ids and counts only: never prompt text, data or output.
            log.info("model.call request={} correlation={} provider={} model={} prompt={}@{} outcome={} attempts={} "
                            + "usage={} tokensIn={} tokensOut={} costUsd={} latencyMs={}", requestId, correlationId, provider,
                    modelId, request.prompt().id(), request.prompt().version(), outcome, count, source, in, out, cost,
                    latency.toMillis());
            return new ModelUsage(source, in, out, cost, latency, count);
        }
    }

    private <R> R inTx(java.util.function.Supplier<R> action) {
        return tx.execute(s -> action.get());
    }

    private static ModelException configuration(UUID requestId, String reason) {
        log.error("Model gateway configuration error: {} (request {})", reason, requestId);
        return new ModelException(Code.CONFIGURATION_ERROR, requestId, null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }
}
