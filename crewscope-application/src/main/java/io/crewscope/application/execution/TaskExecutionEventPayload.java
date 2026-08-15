package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeArtifactKind;
import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;
import java.util.Optional;

/** Closed, sanitized Task runtime event family suitable for later durable AgentRun mapping. */
public sealed interface TaskExecutionEventPayload {

    default Optional<TaskExecutionTerminalStatus> terminalStatus() {
        return Optional.empty();
    }

    record Started(AgentRunSegmentKind segmentKind) implements TaskExecutionEventPayload {
        public Started {
            segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
        }
    }

    record TextDelta(String text) implements TaskExecutionEventPayload {
        public TextDelta {
            text = Objects.requireNonNull(text, "text");
            if (text.isEmpty() || text.length() > 10_000) {
                throw new IllegalArgumentException(
                        "text must contain between 1 and 10000 characters");
            }
        }
    }

    /** Safe summary only; private chain-of-thought is outside the platform protocol. */
    record ThinkingSummary(String summary) implements TaskExecutionEventPayload {
        public ThinkingSummary {
            summary = requireText(summary, "summary", 2_000);
        }
    }

    record StructuredOutput<T>(StructuredOutputSpec<T> spec, T value)
            implements TaskExecutionEventPayload {
        public StructuredOutput {
            spec = Objects.requireNonNull(spec, "spec");
            value = spec.requireValue(value);
        }
    }

    record PlanChanged(
            TaskFactHash contentHash,
            Optional<PlanVersionId> publishedPlanVersionId)
            implements TaskExecutionEventPayload {
        public PlanChanged {
            contentHash = Objects.requireNonNull(contentHash, "contentHash");
            publishedPlanVersionId = Objects.requireNonNull(
                    publishedPlanVersionId, "publishedPlanVersionId");
        }
    }

    record ToolStarted(String toolCallId, String toolName) implements TaskExecutionEventPayload {
        public ToolStarted {
            toolCallId = requireText(toolCallId, "toolCallId", 200);
            toolName = requireTool(toolName);
        }
    }

    record ToolResult(
            String toolCallId,
            String toolName,
            boolean succeeded,
            Optional<RuntimeArtifactId> artifactId,
            Optional<ExecutionFailure> failure)
            implements TaskExecutionEventPayload {
        public ToolResult {
            toolCallId = requireText(toolCallId, "toolCallId", 200);
            toolName = requireTool(toolName);
            artifactId = Objects.requireNonNull(artifactId, "artifactId");
            failure = Objects.requireNonNull(failure, "failure");
            if (succeeded == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "failure must exist exactly for an unsuccessful Tool result");
            }
        }
    }

    record Progress(String safeSummary, Optional<Integer> percent)
            implements TaskExecutionEventPayload {
        public Progress {
            safeSummary = requireText(safeSummary, "safeSummary", 1_000);
            percent = Objects.requireNonNull(percent, "percent");
            if (percent.filter(value -> value < 0 || value > 100).isPresent()) {
                throw new IllegalArgumentException("percent must be between 0 and 100");
            }
        }
    }

    record ArtifactCreated(RuntimeArtifactId artifactId, RuntimeArtifactKind kind)
            implements TaskExecutionEventPayload {
        public ArtifactCreated {
            artifactId = Objects.requireNonNull(artifactId, "artifactId");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    record ApprovalRequired(
            ExecutionInterruptToken token,
            ExecutionInterruptKind kind,
            String safePrompt)
            implements TaskExecutionEventPayload {
        public ApprovalRequired {
            token = Objects.requireNonNull(token, "token");
            kind = Objects.requireNonNull(kind, "kind");
            safePrompt = requireText(safePrompt, "safePrompt", 1_000);
        }

        @Override
        public Optional<TaskExecutionTerminalStatus> terminalStatus() {
            return Optional.of(TaskExecutionTerminalStatus.INTERRUPTED);
        }
    }

    record StatusChanged(TaskExecutionRuntimePhase phase) implements TaskExecutionEventPayload {
        public StatusChanged {
            phase = Objects.requireNonNull(phase, "phase");
        }
    }

    record UsageReported(
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long totalTokens)
            implements TaskExecutionEventPayload {
        public UsageReported {
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

    /** Safe model-control transition without Provider endpoint or credential disclosure. */
    record ModelTransition(
            ModelTransitionType type,
            ModelRole modelRole,
            int attempt,
            int maxAttempts)
            implements TaskExecutionEventPayload {
        public ModelTransition {
            type = Objects.requireNonNull(type, "type");
            modelRole = Objects.requireNonNull(modelRole, "modelRole");
            if (attempt < 1 || maxAttempts < 1 || attempt > maxAttempts) {
                throw new IllegalArgumentException(
                        "attempt must be between 1 and maxAttempts");
            }
            if (type == ModelTransitionType.FALLBACK_SELECTED
                    && modelRole != ModelRole.FALLBACK) {
                throw new IllegalArgumentException(
                        "fallback selection must identify the FALLBACK model role");
            }
        }
    }

    record Completed(Optional<RuntimeArtifactId> resultArtifactId)
            implements TaskExecutionEventPayload {
        public Completed {
            resultArtifactId = Objects.requireNonNull(resultArtifactId, "resultArtifactId");
        }

        @Override
        public Optional<TaskExecutionTerminalStatus> terminalStatus() {
            return Optional.of(TaskExecutionTerminalStatus.COMPLETED);
        }
    }

    record Paused(ExecutionInterruptToken token, String reason) implements TaskExecutionEventPayload {
        public Paused {
            token = Objects.requireNonNull(token, "token");
            reason = requireText(reason, "reason", 500);
        }

        @Override
        public Optional<TaskExecutionTerminalStatus> terminalStatus() {
            return Optional.of(TaskExecutionTerminalStatus.PAUSED);
        }
    }

    enum ModelTransitionType {
        RETRYING,
        FALLBACK_SELECTED
    }

    enum ModelRole {
        PRIMARY,
        FALLBACK
    }

    record Canceled(String reason) implements TaskExecutionEventPayload {
        public Canceled {
            reason = requireText(reason, "reason", 500);
        }

        @Override
        public Optional<TaskExecutionTerminalStatus> terminalStatus() {
            return Optional.of(TaskExecutionTerminalStatus.CANCELED);
        }
    }

    record Failed(ExecutionFailure failure) implements TaskExecutionEventPayload {
        public Failed {
            failure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public Optional<TaskExecutionTerminalStatus> terminalStatus() {
            return Optional.of(TaskExecutionTerminalStatus.FAILED);
        }
    }

    private static String requireTool(String value) {
        String required = Objects.requireNonNull(value, "toolName").strip();
        if (!required.matches("[a-z][a-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("toolName must use a stable lowercase key");
        }
        return required;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty()
                || required.length() > maximumLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must contain printable bounded text");
        }
        return required;
    }
}
