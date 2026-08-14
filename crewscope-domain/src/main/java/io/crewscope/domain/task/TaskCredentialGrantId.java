package io.crewscope.domain.task;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Strongly typed identity of one persisted Task Token authorization grant. */
public record TaskCredentialGrantId(UUID value) implements AggregateId {

    public TaskCredentialGrantId {
        value = AggregateId.requireValue(value, "TaskCredentialGrantId");
    }

    public static TaskCredentialGrantId generate() {
        return new TaskCredentialGrantId(AggregateId.generateValue());
    }

    public static TaskCredentialGrantId from(String value) {
        return new TaskCredentialGrantId(
                AggregateId.parseCanonical(value, "TaskCredentialGrantId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
