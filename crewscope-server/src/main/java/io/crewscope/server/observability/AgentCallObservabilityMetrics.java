package io.crewscope.server.observability;

import io.crewscope.agentscope.AgentCallObservationEvent;
import io.crewscope.agentscope.AgentCallObservationRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Records Agent model telemetry using only bounded platform-controlled tag values. */
public final class AgentCallObservabilityMetrics {

    public static final String CALLS = "crewscope.agent.model.calls";
    public static final String TOKENS = "crewscope.agent.model.tokens";
    public static final String RETRIES = "crewscope.agent.model.retries";
    public static final String FALLBACKS = "crewscope.agent.model.fallbacks";
    public static final String ERRORS = "crewscope.agent.model.errors";

    private final MeterRegistry registry;

    public AgentCallObservabilityMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Projects one safe observation without using IDs, model names or provider text as tags. */
    public void record(AgentCallObservationRecord observation) {
        AgentCallObservationRecord required = Objects.requireNonNull(observation, "observation");
        String role = required.modelRole().name().toLowerCase(Locale.ROOT);
        if (required.event() == AgentCallObservationEvent.RETRYING) {
            Counter.builder(RETRIES).tag("role", role).register(registry).increment();
            return;
        }
        if (required.event() == AgentCallObservationEvent.FALLBACK_SELECTED) {
            Counter.builder(FALLBACKS).register(registry).increment();
            return;
        }
        if (!isTerminal(required.event())) {
            return;
        }

        String outcome = required.event().name().toLowerCase(Locale.ROOT);
        Timer.builder(CALLS)
                .tags("outcome", outcome, "fallback", Boolean.toString(required.fallbackUsed()))
                .register(registry)
                .record(Duration.ofMillis(required.latencyMillis()));
        if (required.event() == AgentCallObservationEvent.COMPLETED) {
            incrementTokens("input", required.inputTokens());
            incrementTokens("output", required.outputTokens());
            incrementTokens("cached", required.cachedTokens());
        }
        required.safeErrorCode().ifPresent(code -> Counter.builder(ERRORS)
                .tags("code", code, "role", role)
                .register(registry)
                .increment());
    }

    private void incrementTokens(String type, int amount) {
        Counter.builder(TOKENS).tag("type", type).register(registry).increment(amount);
    }

    private static boolean isTerminal(AgentCallObservationEvent event) {
        return event == AgentCallObservationEvent.COMPLETED
                || event == AgentCallObservationEvent.FAILED
                || event == AgentCallObservationEvent.CANCELED;
    }
}
