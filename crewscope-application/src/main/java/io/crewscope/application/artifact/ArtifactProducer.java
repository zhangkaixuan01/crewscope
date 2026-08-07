package io.crewscope.application.artifact;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Trusted producer coordinates used for ownership, recovery and trace correlation. */
public record ArtifactProducer(
        PrincipalId principalId,
        Optional<UUID> taskExecutionId,
        Optional<UUID> stepExecutionId,
        Optional<UUID> agentRunId,
        Optional<String> traceId) {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";

    public ArtifactProducer {
        Objects.requireNonNull(principalId, "principalId");
        taskExecutionId = requireUuid(taskExecutionId, "taskExecutionId");
        stepExecutionId = requireUuid(stepExecutionId, "stepExecutionId");
        agentRunId = requireUuid(agentRunId, "agentRunId");
        traceId = normalizeTraceId(traceId);
    }

    public static ArtifactProducer principal(PrincipalId principalId) {
        return new ArtifactProducer(
                principalId,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Optional<UUID> requireUuid(Optional<UUID> value, String name) {
        Optional<UUID> required = Objects.requireNonNull(value, name);
        required.ifPresent(id -> {
            if (AggregateId.NIL_UUID.equals(id)) {
                throw new IllegalArgumentException(name + " must not use the nil UUID");
            }
        });
        return required;
    }

    private static Optional<String> normalizeTraceId(Optional<String> value) {
        Optional<String> required = Objects.requireNonNull(value, "traceId");
        if (required.isEmpty()) {
            return required;
        }
        String normalized = required.orElseThrow().strip().toLowerCase(Locale.ROOT);
        if (!TRACE_ID.matcher(normalized).matches() || ZERO_TRACE_ID.equals(normalized)) {
            throw new IllegalArgumentException("traceId must be a non-zero W3C Trace ID");
        }
        return Optional.of(normalized);
    }
}
