package io.crewscope.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/** Preserves AgentScope retry semantics while exposing real attempts and sanitizing terminal errors. */
final class ObservableAgentScopeModel implements Model {

    private final Model delegate;
    private final AgentModelRole role;

    ObservableAgentScopeModel(Model delegate, AgentModelRole role) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (role != AgentModelRole.PRIMARY && role != AgentModelRole.FALLBACK) {
            throw new IllegalArgumentException("observable model role must be PRIMARY or FALLBACK");
        }
        this.role = role;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(contextView -> AgentCallObservationScope.find(contextView)
                .<Flux<ChatResponse>>map(scope -> observed(scope, messages, tools, options))
                .orElseGet(() -> Flux.defer(() -> delegate.stream(messages, tools, options))));
    }

    private Flux<ChatResponse> observed(
            AgentCallObservationScope scope,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        ExecutionConfig effective = ExecutionConfig.mergeConfigs(
                options == null ? null : options.getExecutionConfig(),
                ExecutionConfig.MODEL_DEFAULTS);
        int maxAttempts = effective.getMaxAttempts() == null ? 1 : effective.getMaxAttempts();
        GenerateOptions singleAttemptOptions = singleAttemptOptions(options);
        scope.modelSelected(role);
        if (role == AgentModelRole.FALLBACK) {
            scope.fallbackSelected(getModelName(), maxAttempts);
        }

        Flux<ChatResponse> attempts = Flux.defer(
                () -> delegate.stream(messages, tools, singleAttemptOptions));
        if (maxAttempts > 1) {
            Duration initialBackoff = Objects.requireNonNullElse(
                    effective.getInitialBackoff(), Duration.ofSeconds(1));
            Duration maxBackoff = Objects.requireNonNullElse(
                    effective.getMaxBackoff(), Duration.ofSeconds(10));
            Predicate<Throwable> retryOn = Objects.requireNonNullElse(
                    effective.getRetryOn(), ignored -> true);
            Retry retry = Retry.backoff(maxAttempts - 1, initialBackoff)
                    .maxBackoff(maxBackoff)
                    .jitter(0.5)
                    .filter(retryOn)
                    .doBeforeRetry(signal -> scope.retrying(
                            getModelName(),
                            role,
                            Math.toIntExact(signal.totalRetries() + 2),
                            maxAttempts));
            attempts = attempts.retryWhen(retry);
        }
        return attempts.onErrorMap(failure -> new SafeModelExecutionException(
                AgentCallFailureClassifier.classify(AgentCallFailureClassifier.unwrap(failure))));
    }

    private static GenerateOptions singleAttemptOptions(GenerateOptions original) {
        ExecutionConfig originalExecution = original == null ? null : original.getExecutionConfig();
        ExecutionConfig singleExecution = ExecutionConfig.mergeConfigs(
                ExecutionConfig.builder().maxAttempts(1).build(), originalExecution);
        GenerateOptions override = GenerateOptions.builder()
                .executionConfig(singleExecution)
                .build();
        return GenerateOptions.mergeOptions(override, original);
    }

    @Override
    public String getModelName() {
        return AgentCallObservationRecord.safeModelName(delegate.getModelName());
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    Model delegate() {
        return delegate;
    }

    AgentModelRole role() {
        return role;
    }
}
