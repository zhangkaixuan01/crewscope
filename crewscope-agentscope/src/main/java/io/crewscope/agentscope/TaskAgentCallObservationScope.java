package io.crewscope.agentscope;

import io.crewscope.application.execution.TaskExecutionEventPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Reactor-local bridge from AgentScope model control to the ordered Task runtime event stream. */
public final class TaskAgentCallObservationScope {

    private static final Class<TaskAgentCallObservationScope> KEY =
            TaskAgentCallObservationScope.class;

    private final Consumer<TaskExecutionEventPayload.ModelTransition> observer;

    private TaskAgentCallObservationScope(
            Consumer<TaskExecutionEventPayload.ModelTransition> observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public static Function<Context, Context> install(
            Consumer<TaskExecutionEventPayload.ModelTransition> observer) {
        TaskAgentCallObservationScope scope = new TaskAgentCallObservationScope(observer);
        return context -> context.put(KEY, scope);
    }

    static Optional<TaskAgentCallObservationScope> find(ContextView contextView) {
        return contextView.getOrEmpty(KEY);
    }

    void retrying(AgentModelRole role, int attempt, int maxAttempts) {
        observer.accept(new TaskExecutionEventPayload.ModelTransition(
                TaskExecutionEventPayload.ModelTransitionType.RETRYING,
                modelRole(role),
                attempt,
                maxAttempts));
    }

    void fallbackSelected(int maxAttempts) {
        observer.accept(new TaskExecutionEventPayload.ModelTransition(
                TaskExecutionEventPayload.ModelTransitionType.FALLBACK_SELECTED,
                TaskExecutionEventPayload.ModelRole.FALLBACK,
                1,
                maxAttempts));
    }

    private static TaskExecutionEventPayload.ModelRole modelRole(AgentModelRole role) {
        return switch (role) {
            case PRIMARY -> TaskExecutionEventPayload.ModelRole.PRIMARY;
            case FALLBACK -> TaskExecutionEventPayload.ModelRole.FALLBACK;
            default -> throw new IllegalArgumentException("Task model role must be primary or fallback");
        };
    }
}
