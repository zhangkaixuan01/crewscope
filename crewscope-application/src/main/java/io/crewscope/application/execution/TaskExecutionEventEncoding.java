package io.crewscope.application.execution;

import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.event.AgentRunEventRecorded;
import java.util.Objects;

/** Full-event fingerprint plus its deliberately reduced public DomainEvent payload. */
public record TaskExecutionEventEncoding(
        RuntimeContentHash fingerprint, AgentRunEventRecorded publicEvent) {

    public TaskExecutionEventEncoding {
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        publicEvent = Objects.requireNonNull(publicEvent, "publicEvent");
    }
}
