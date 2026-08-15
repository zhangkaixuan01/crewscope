package io.crewscope.application.task;

import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.domain.shared.id.AggregateId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted request metadata; execution identity comes only from the verified Task Token. */
public record WorkerCommandContext(
        TaskTokenExecutionContext authorization,
        IdempotencyKey idempotencyKey,
        UUID correlationId,
        Optional<UUID> causationId) {

    public WorkerCommandContext {
        authorization = Objects.requireNonNull(authorization, "authorization");
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        correlationId = requireId(correlationId, "correlationId");
        causationId = Objects.requireNonNull(causationId, "causationId")
                .map(value -> requireId(value, "causationId"));
    }

    private static UUID requireId(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }

    @Override
    public String toString() {
        return "WorkerCommandContext[taskExecutionId="
                + authorization.scope().taskExecutionId()
                + ", authorization=[REDACTED]]";
    }
}
