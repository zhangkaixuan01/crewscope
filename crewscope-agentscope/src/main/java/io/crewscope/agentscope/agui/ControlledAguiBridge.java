package io.crewscope.agentscope.agui;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import io.crewscope.application.execution.PlatformExecutionContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs the official AgentScope AG-UI adapter behind CrewScope's server-resolved security boundary.
 */
public final class ControlledAguiBridge {

    private static final AguiAdapterConfig CONTROLLED_CONFIG = AguiAdapterConfig.builder()
            .toolMergeMode(ToolMergeMode.AGENT_ONLY)
            .emitStateEvents(false)
            .emitToolCallArgs(false)
            .enableReasoning(false)
            .build();

    private final AguiEventSanitizer eventSanitizer = new AguiEventSanitizer();

    static AguiAdapterConfig controlledConfig() {
        return CONTROLLED_CONFIG;
    }

    /**
     * Rebuilds protocol input and RuntimeContext exclusively from trusted server-side facts.
     *
     * @param agent server-resolved Agent instance
     * @param invocation trusted Conversation, Principal and Session binding
     * @param clientInput message-only client input
     * @return AG-UI event stream carrying only server-generated Thread and Run identifiers
     */
    public Flux<AguiEvent> run(
            Agent agent,
            ServerResolvedAguiInvocation invocation,
            ControlledAguiClientInput clientInput) {
        Agent requiredAgent = Objects.requireNonNull(agent, "agent");
        ServerResolvedAguiInvocation requiredInvocation = Objects.requireNonNull(
                invocation, "invocation");
        ControlledAguiClientInput requiredInput = Objects.requireNonNull(
                clientInput, "clientInput");
        if (!requiredInvocation.resolvedAgentId().equals(requiredAgent.getAgentId())) {
            throw new IllegalArgumentException(
                    "Resolved Agent does not match the server invocation binding");
        }

        // Never copy client controls into the official protocol model. Empty collections are
        // deliberate defense in depth in addition to AGENT_ONLY and strict DTO parsing.
        RunAgentInput safeInput = RunAgentInput.builder()
                .threadId(requiredInvocation.threadId())
                .runId(requiredInvocation.runId())
                .messages(List.of(AguiMessage.userMessage(
                        requiredInvocation.inputMessageId().toString(),
                        requiredInput.getMessage())))
                .tools(List.of())
                .context(List.of())
                .state(Map.of())
                .forwardedProps(Map.of())
                .build();

        Agent boundAgent = new RuntimeContextBoundAgent(
                requiredAgent, requiredInvocation, safeInput);
        return new AguiAgentAdapter(boundAgent, CONTROLLED_CONFIG)
                .run(safeInput)
                .handle((event, sink) -> eventSanitizer.sanitize(event).ifPresent(sink::next));
    }

    /** Replaces the RuntimeContext created by the generic adapter with the trusted state key. */
    @SuppressWarnings({"deprecation", "removal"})
    private static final class RuntimeContextBoundAgent implements Agent {

        private final Agent delegate;
        private final ServerResolvedAguiInvocation invocation;
        private final RunAgentInput safeInput;

        private RuntimeContextBoundAgent(
                Agent delegate,
                ServerResolvedAguiInvocation invocation,
                RunAgentInput safeInput) {
            this.delegate = delegate;
            this.invocation = invocation;
            this.safeInput = safeInput;
        }

        @Override
        public Flux<Event> stream(
                List<Msg> messages, StreamOptions options, RuntimeContext ignoredContext) {
            return delegate.stream(messages, options, trustedRuntimeContext());
        }

        private RuntimeContext trustedRuntimeContext() {
            return RuntimeContext.builder()
                    .userId(invocation.agentScopeSessionKey().userId())
                    .sessionId(invocation.agentScopeSessionKey().sessionId())
                    .put(ServerResolvedAguiInvocation.class, invocation)
                    .put(PlatformExecutionContext.class, invocation.platformContext())
                    .put(RunAgentInput.class, safeInput)
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_THREAD_ID_KEY, safeInput.getThreadId())
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_RUN_ID_KEY, safeInput.getRunId())
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_MESSAGES_KEY, safeInput.getMessages())
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_TOOLS_KEY, List.of())
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_CONTEXT_KEY, List.of())
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_STATE_KEY, Map.of())
                    .put(AguiAgentAdapter.RUNTIME_CONTEXT_FORWARDED_PROPS_KEY, Map.of())
                    .build();
        }

        @Override
        public String getAgentId() {
            return delegate.getAgentId();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public void interrupt() {
            delegate.interrupt();
        }

        @Override
        public void interrupt(Msg message) {
            delegate.interrupt(message);
        }

        @Override
        public io.agentscope.core.state.AgentState getAgentState() {
            return delegate.getAgentState();
        }

        @Override
        public Toolkit getToolkit() {
            return delegate.getToolkit();
        }

        @Override
        public Mono<Msg> call(List<Msg> messages) {
            return delegate.call(messages);
        }

        @Override
        public Mono<Msg> call(List<Msg> messages, Class<?> structuredModel) {
            return delegate.call(messages, structuredModel);
        }

        @Override
        public Mono<Msg> call(List<Msg> messages, JsonNode schema) {
            return delegate.call(messages, schema);
        }

        @Override
        public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
            return delegate.stream(messages, options);
        }

        @Override
        public Flux<Event> stream(
                List<Msg> messages, StreamOptions options, Class<?> structuredModel) {
            return delegate.stream(messages, options, structuredModel);
        }

        @Override
        public Flux<Event> stream(
                List<Msg> messages, StreamOptions options, JsonNode schema) {
            return delegate.stream(messages, options, schema);
        }

        @Override
        public Mono<Void> observe(Msg message) {
            return delegate.observe(message);
        }

        @Override
        public Mono<Void> observe(List<Msg> messages) {
            return delegate.observe(messages);
        }
    }
}
