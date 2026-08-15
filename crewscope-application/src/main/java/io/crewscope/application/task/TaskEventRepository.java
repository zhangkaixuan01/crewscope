package io.crewscope.application.task;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.DomainEventEnvelope;

/** Durable Task Event projection Port. */
public interface TaskEventRepository {

    /** Appends a Task stream index in the caller's existing business transaction. */
    void append(
            TaskEventContext context,
            DomainEventEnvelope<? extends DomainEvent> domainEvent);

    /** Reads the next ascending page and validates any supplied durable position. */
    TaskEventPage findPage(TaskEventQuery query, boolean taskTerminal);
}
