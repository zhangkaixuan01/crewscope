package io.crewscope.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.crewscope.domain.model.ModelCallAttribution;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

/** Preserves AgentScope retry semantics while exposing real attempts and sanitizing terminal errors. */
public final class ObservableAgentScopeModel implements Model {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservableAgentScopeModel.class);

    private final Model delegate;
    private final AgentModelRole role;
    private final ExecutionConfig platformDefaults;
    private final ModelCallAttribution attribution;

    public ObservableAgentScopeModel(Model delegate, AgentModelRole role) {
        this(delegate, role, null, null);
    }

    public ObservableAgentScopeModel(
            Model delegate, AgentModelRole role, ExecutionConfig platformDefaults) {
        this(delegate, role, platformDefaults, null);
    }

    /**
     * Full construction with usage-fact coordinates (M10-F03). The attribution lives on the
     * cached instance, so every execution reusing it reports the same coordinates; env-slot
     * models keep {@code null} and their realtime usage is reported without a fact.
     */
    public ObservableAgentScopeModel(
            Model delegate,
            AgentModelRole role,
            ExecutionConfig platformDefaults,
            ModelCallAttribution attribution) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (role != AgentModelRole.PRIMARY && role != AgentModelRole.FALLBACK) {
            throw new IllegalArgumentException("observable model role must be PRIMARY or FALLBACK");
        }
        this.role = role;
        this.platformDefaults = platformDefaults;
        this.attribution = attribution;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            ModelToolNamePolicy.requireCompatibleSchemas(tools);
            return Flux.deferContextual(contextView -> {
                AgentCallObservationScope invocationScope =
                        AgentCallObservationScope.find(contextView).orElse(null);
                TaskAgentCallObservationScope taskScope =
                        TaskAgentCallObservationScope.find(contextView).orElse(null);
                if (invocationScope == null && taskScope == null) {
                    return Flux.defer(() -> delegate.stream(messages, tools, options));
                }
                return observed(invocationScope, taskScope, messages, tools, options);
            });
        });
    }

    private Flux<ChatResponse> observed(
            AgentCallObservationScope invocationScope,
            TaskAgentCallObservationScope taskScope,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        ExecutionConfig effective = ExecutionConfig.mergeConfigs(
                options == null ? null : options.getExecutionConfig(),
                ExecutionConfig.mergeConfigs(platformDefaults, ExecutionConfig.MODEL_DEFAULTS));
        int maxAttempts = effective.getMaxAttempts() == null ? 1 : effective.getMaxAttempts();
        GenerateOptions singleAttemptOptions = singleAttemptOptions(options);
        if (invocationScope != null) {
            invocationScope.modelSelected(role);
        }
        if (taskScope != null) {
            // Announce the serving instance's coordinates before any token flows: the Task
            // runtime attributes the next cumulative UsageReported to this stream (M10-F03).
            taskScope.modelAttribution(attribution);
        }
        if (role == AgentModelRole.FALLBACK) {
            if (invocationScope != null) {
                invocationScope.fallbackSelected(getModelName(), maxAttempts);
            }
            if (taskScope != null) {
                taskScope.fallbackSelected(maxAttempts);
            }
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
                    .doBeforeRetry(signal -> {
                        int nextAttempt = Math.toIntExact(signal.totalRetries() + 2);
                        if (invocationScope != null) {
                            invocationScope.retrying(
                                    getModelName(), role, nextAttempt, maxAttempts);
                        }
                        if (taskScope != null) {
                            taskScope.retrying(role, nextAttempt, maxAttempts);
                        }
                    });
            attempts = attempts.retryWhen(retry);
        }
        return attempts.onErrorMap(failure -> {
            Throwable cause = AgentCallFailureClassifier.unwrap(failure);
            // Defect 20 observation (M9b-Q02): a reviewer call failed in ~49ms with no model
            // request on the wire and nothing logged — the sanitized exception erased both the
            // code and the originating class. Keep the sanitization contract (no provider text)
            // but record the bounded code and the exception class name.
            LOGGER.warn("Model stream terminated [code={}, cause={}]",
                    AgentCallFailureClassifier.classify(cause), cause.getClass().getName());
            return new SafeModelExecutionException(AgentCallFailureClassifier.classify(cause));
        });
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
