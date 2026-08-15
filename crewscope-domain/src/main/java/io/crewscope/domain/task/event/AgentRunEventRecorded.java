package io.crewscope.domain.task.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sanitized durable projection of one Task runtime event.
 *
 * <p>The shape deliberately has no Interrupt Token, ToolCallId, Tool arguments, raw Tool result,
 * Provider error, Agent state or private reasoning field. Large values are represented by a
 * RuntimeArtifact reference.
 */
public record AgentRunEventRecorded(
        UUID taskExecutionId,
        int attempt,
        UUID agentRunId,
        long segmentSequence,
        long eventSequence,
        String eventKind,
        UtcTimestamp runtimeOccurredAt,
        Optional<String> safeText,
        Optional<String> name,
        Optional<String> status,
        Optional<String> referenceType,
        Optional<UUID> referenceId,
        Optional<String> contentHash,
        Optional<Boolean> succeeded,
        Optional<Integer> progressPercent,
        Optional<Integer> modelAttempt,
        Optional<Integer> modelMaxAttempts,
        Optional<TokenUsage> usage,
        Optional<SafeFailure> failure) implements DomainEvent {

    private static final Pattern EVENT_KIND = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_./-]{0,199}");
    private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public AgentRunEventRecorded {
        taskExecutionId = AggregateId.requireValue(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || segmentSequence < 1 || eventSequence < 1) {
            throw new IllegalArgumentException(
                    "attempt, segmentSequence and eventSequence must be positive");
        }
        agentRunId = AggregateId.requireValue(agentRunId, "agentRunId");
        eventKind = requirePattern(eventKind, EVENT_KIND, "eventKind");
        runtimeOccurredAt = Objects.requireNonNull(runtimeOccurredAt, "runtimeOccurredAt");
        safeText = requireOptionalText(safeText, "safeText", 10_000);
        name = requireOptionalPattern(name, NAME, "name");
        status = requireOptionalPattern(status, STATUS, "status");
        referenceType = requireOptionalPattern(referenceType, REFERENCE, "referenceType");
        referenceId = Objects.requireNonNull(referenceId, "referenceId")
                .map(value -> AggregateId.requireValue(value, "referenceId"));
        contentHash = requireOptionalPattern(contentHash, SHA_256, "contentHash");
        succeeded = Objects.requireNonNull(succeeded, "succeeded");
        progressPercent = Objects.requireNonNull(progressPercent, "progressPercent");
        progressPercent.ifPresent(value -> {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("progressPercent must be between 0 and 100");
            }
        });
        modelAttempt = Objects.requireNonNull(modelAttempt, "modelAttempt");
        modelMaxAttempts = Objects.requireNonNull(modelMaxAttempts, "modelMaxAttempts");
        if (modelAttempt.isPresent() != modelMaxAttempts.isPresent()) {
            throw new IllegalArgumentException(
                    "modelAttempt and modelMaxAttempts must be present together");
        }
        if (modelAttempt.isPresent()) {
            int attemptValue = modelAttempt.orElseThrow();
            int maximum = modelMaxAttempts.orElseThrow();
            if (attemptValue < 1 || maximum < 1 || attemptValue > maximum) {
                throw new IllegalArgumentException(
                        "modelAttempt must be between 1 and modelMaxAttempts");
            }
        }
        usage = Objects.requireNonNull(usage, "usage");
        failure = Objects.requireNonNull(failure, "failure");
        if (referenceType.isPresent() != referenceId.isPresent()) {
            throw new IllegalArgumentException(
                    "referenceType and referenceId must be present together");
        }
    }

    private static Optional<String> requireOptionalText(
            Optional<String> value, String field, int maximumLength) {
        return Objects.requireNonNull(value, field)
                .map(candidate -> {
                    String normalized = candidate.strip();
                    if (normalized.isEmpty()
                            || normalized.length() > maximumLength
                            || normalized.chars().anyMatch(Character::isISOControl)) {
                        throw new IllegalArgumentException(field + " contains invalid public text");
                    }
                    return normalized;
                });
    }

    private static Optional<String> requireOptionalPattern(
            Optional<String> value, Pattern pattern, String field) {
        return Objects.requireNonNull(value, field)
                .map(candidate -> requirePattern(candidate, pattern, field));
    }

    private static String requirePattern(String value, Pattern pattern, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has an invalid stable value");
        }
        return normalized;
    }

    /** Cumulative token counters reported by the controlled Runtime. */
    public record TokenUsage(
            long inputTokens, long outputTokens, long cachedTokens, long totalTokens) {
        public TokenUsage {
            if (inputTokens < 0
                    || outputTokens < 0
                    || cachedTokens < 0
                    || totalTokens < 0
                    || cachedTokens > inputTokens
                    || totalTokens != inputTokens + outputTokens) {
                throw new IllegalArgumentException("token usage must be non-negative and consistent");
            }
        }
    }

    /** Stable safe failure disclosed without exception or Provider payload. */
    public record SafeFailure(
            String category,
            boolean retryable,
            String safeMessage,
            Optional<String> runtimeCode) {
        public SafeFailure {
            category = requirePattern(category, STATUS, "failure.category");
            safeMessage = requireOptionalText(
                            Optional.ofNullable(safeMessage), "failure.safeMessage", 500)
                    .orElseThrow();
            runtimeCode = requireOptionalPattern(runtimeCode, CODE, "failure.runtimeCode");
        }
    }
}
