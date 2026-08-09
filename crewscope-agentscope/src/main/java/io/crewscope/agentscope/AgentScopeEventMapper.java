package io.crewscope.agentscope;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Whitelists AgentScope 2.0.0 events before any value crosses into Application contracts.
 *
 * <p>Thinking, Tool arguments/results, Data blocks, metadata, Custom events and subagent events are
 * intentionally classified as ignored. Control events remain inside this adapter and are reduced
 * to CrewScope's safe interrupt or terminal contracts by {@link AgentScopeNativeRuntime}.
 */
final class AgentScopeEventMapper {

    private final Set<String> seenEventIds = new HashSet<>();

    Mapped map(AgentEvent event) {
        AgentEvent required = Objects.requireNonNull(event, "event");
        String eventId = required.getId();
        if (eventId == null || eventId.isBlank() || !seenEventIds.add(eventId)) {
            return Ignored.INSTANCE;
        }
        if (required.getSource() != null && !required.getSource().isBlank()) {
            // M2 disables subagents; forwarded child content cannot become parent-visible output.
            return Ignored.INSTANCE;
        }
        if (required instanceof TextBlockDeltaEvent delta
                && delta.getDelta() != null
                && !delta.getDelta().isEmpty()) {
            return new PublicText(delta.getDelta());
        }
        if (required instanceof RequireUserConfirmEvent confirmation) {
            return new UserConfirmation(confirmation);
        }
        if (required instanceof RequireExternalExecutionEvent externalExecution) {
            return new ExternalExecution(externalExecution);
        }
        if (required instanceof RequestStopEvent stop) {
            return new Stop(stop);
        }
        if (required instanceof ExceedMaxItersEvent) {
            return MaxIterations.INSTANCE;
        }
        if (required instanceof AgentResultEvent result) {
            return new Result(result);
        }
        return Ignored.INSTANCE;
    }

    sealed interface Mapped {}

    record PublicText(String delta) implements Mapped {
        PublicText {
            delta = Objects.requireNonNull(delta, "delta");
        }
    }

    record UserConfirmation(RequireUserConfirmEvent event) implements Mapped {
        UserConfirmation {
            event = Objects.requireNonNull(event, "event");
        }
    }

    record ExternalExecution(RequireExternalExecutionEvent event) implements Mapped {
        ExternalExecution {
            event = Objects.requireNonNull(event, "event");
        }
    }

    record Stop(RequestStopEvent event) implements Mapped {
        Stop {
            event = Objects.requireNonNull(event, "event");
        }
    }

    record Result(AgentResultEvent event) implements Mapped {
        Result {
            event = Objects.requireNonNull(event, "event");
        }
    }

    enum MaxIterations implements Mapped {
        INSTANCE
    }

    enum Ignored implements Mapped {
        INSTANCE
    }
}
