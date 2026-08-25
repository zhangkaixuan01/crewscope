package io.crewscope.domain.audit;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Stable identity of one append-only security audit fact. */
public record AuditEventId(UUID value) implements AggregateId {

    public AuditEventId {
        value = AggregateId.requireValue(value, "AuditEventId");
    }

    public static AuditEventId generate() {
        return new AuditEventId(AggregateId.generateValue());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
