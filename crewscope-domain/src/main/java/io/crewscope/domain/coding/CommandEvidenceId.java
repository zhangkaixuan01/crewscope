package io.crewscope.domain.coding;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one immutable platform-observed command execution. */
public record CommandEvidenceId(UUID value) implements AggregateId {

    public CommandEvidenceId {
        value = AggregateId.requireValue(value, "CommandEvidenceId");
    }

    public static CommandEvidenceId generate() {
        return new CommandEvidenceId(AggregateId.generateValue());
    }

    public static CommandEvidenceId from(String value) {
        return new CommandEvidenceId(AggregateId.parseCanonical(value, "CommandEvidenceId"));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
