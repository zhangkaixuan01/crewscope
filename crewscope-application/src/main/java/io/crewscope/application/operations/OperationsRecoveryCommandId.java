package io.crewscope.application.operations;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.UUID;

/** Organization-scoped idempotency identity of one operations recovery command. */
public record OperationsRecoveryCommandId(UUID value) implements AggregateId {

    public OperationsRecoveryCommandId {
        value = AggregateId.requireValue(value, "OperationsRecoveryCommandId");
    }

    public static OperationsRecoveryCommandId generate() {
        return new OperationsRecoveryCommandId(AggregateId.generateValue());
    }
}
