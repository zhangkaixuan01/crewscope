package io.crewscope.agentscope.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.crewscope.agentscope.SafeModelFailures;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/** Freezes connection and generation settings while retaining AgentScope's orchestration fields. */
final class ConnectionBoundAgentScopeModel implements Model {

    private final Model delegate;
    private final GenerateOptions safeDefaults;

    ConnectionBoundAgentScopeModel(Model delegate, GenerateOptions safeDefaults) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.safeDefaults = Objects.requireNonNull(safeDefaults, "safeDefaults");
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions requested) {
        GenerateOptions orchestration = requested == null
                ? null
                : GenerateOptions.builder()
                        .responseFormat(requested.getResponseFormat())
                        .toolChoice(requested.getToolChoice())
                        .build();
        return delegate
                .stream(messages, tools, GenerateOptions.mergeOptions(orchestration, safeDefaults))
                .onErrorMap(SafeModelFailures::sanitize);
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
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
}
