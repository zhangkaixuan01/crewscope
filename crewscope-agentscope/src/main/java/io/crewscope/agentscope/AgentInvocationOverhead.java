package io.crewscope.agentscope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Invocation-acceptance anchor for the application-overhead SLI (M12-I03): the invocation
 * middleware installs it into the Reactor Context and the first model call reads it, so the
 * gap "acceptance to first model-call initiation" travels the async chain without mutable
 * shared state beyond the one-shot record guard.
 */
final class AgentInvocationOverhead {

    private static final Class<AgentInvocationOverhead> KEY = AgentInvocationOverhead.class;

    private final Instant invocationStartedAt;
    private final AtomicBoolean recorded = new AtomicBoolean();

    AgentInvocationOverhead(Instant invocationStartedAt) {
        this.invocationStartedAt = Objects.requireNonNull(invocationStartedAt, "invocationStartedAt");
    }

    static Optional<AgentInvocationOverhead> find(ContextView contextView) {
        return contextView.hasKey(KEY)
                ? Optional.of(contextView.get(KEY))
                : Optional.empty();
    }

    Context install(Context context) {
        return context.put(KEY, this);
    }

    /** Claims the one application-overhead record for this invocation. */
    boolean tryRecord() {
        return recorded.compareAndSet(false, true);
    }

    long elapsedMillis(Clock clock) {
        return Math.max(0L, Duration.between(invocationStartedAt, clock.instant()).toMillis());
    }
}
