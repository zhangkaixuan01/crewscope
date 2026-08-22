package io.crewscope.infrastructure.runtime;

import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventEncoder;
import io.crewscope.application.execution.TaskExecutionEventEncoding;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.event.AgentRunEventRecorded;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Canonical full-event fingerprint and deliberately reduced public-event mapper. */
@Component
public final class JacksonTaskExecutionEventEncoder implements TaskExecutionEventEncoder {

    private final ObjectMapper objectMapper;

    public JacksonTaskExecutionEventEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public TaskExecutionEventEncoding encode(TaskExecutionEvent event) {
        TaskExecutionEvent required = Objects.requireNonNull(event, "event");
        RuntimeContentHash fingerprint = RuntimeContentHash.sha256(canonical(required));
        return new TaskExecutionEventEncoding(fingerprint, publicEvent(required));
    }

    private String canonical(TaskExecutionEvent event) {
        StringBuilder value = new StringBuilder(512);
        append(value, event.taskExecutionId().toString());
        append(value, event.attempt());
        append(value, event.agentRunId().toString());
        append(value, event.segmentSequence());
        append(value, event.sequence());
        append(value, event.occurredAt().toString());
        TaskExecutionEventPayload payload = event.payload();
        append(value, eventKind(payload));
        if (payload instanceof TaskExecutionEventPayload.Started started) {
            append(value, started.segmentKind());
        } else if (payload instanceof TaskExecutionEventPayload.TextDelta text) {
            append(value, text.text());
        } else if (payload instanceof TaskExecutionEventPayload.ThinkingSummary thinking) {
            append(value, thinking.summary());
        } else if (payload instanceof TaskExecutionEventPayload.StructuredOutput<?> structured) {
            append(value, structured.spec().schemaId());
            append(value, structured.spec().javaType().getName());
            append(value, json(structured.value()));
        } else if (payload instanceof TaskExecutionEventPayload.PlanChanged plan) {
            append(value, plan.contentHash().value());
            append(value, plan.publishedPlanVersionId().map(Object::toString).orElse(""));
        } else if (payload instanceof TaskExecutionEventPayload.ToolStarted tool) {
            append(value, tool.toolCallId());
            append(value, tool.toolName());
        } else if (payload instanceof TaskExecutionEventPayload.ToolResult result) {
            append(value, result.toolCallId());
            append(value, result.toolName());
            append(value, result.succeeded());
            append(value, result.artifactId().map(Object::toString).orElse(""));
            appendFailure(value, result.failure());
        } else if (payload instanceof TaskExecutionEventPayload.Progress progress) {
            append(value, progress.safeSummary());
            append(value, progress.percent().map(Object::toString).orElse(""));
        } else if (payload instanceof TaskExecutionEventPayload.ArtifactCreated artifact) {
            append(value, artifact.artifactId().toString());
            append(value, artifact.kind());
        } else if (payload instanceof TaskExecutionEventPayload.ApprovalRequired approval) {
            append(value, approval.token().value());
            append(value, approval.kind());
            append(value, approval.safePrompt());
        } else if (payload instanceof TaskExecutionEventPayload.StatusChanged status) {
            append(value, status.phase());
        } else if (payload instanceof TaskExecutionEventPayload.UsageReported usage) {
            appendUsage(value, usage);
        } else if (payload instanceof TaskExecutionEventPayload.ModelTransition transition) {
            append(value, transition.type());
            append(value, transition.modelRole());
            append(value, transition.attempt());
            append(value, transition.maxAttempts());
        } else if (payload instanceof TaskExecutionEventPayload.Completed completed) {
            append(value, completed.resultArtifactId().map(Object::toString).orElse(""));
        } else if (payload instanceof TaskExecutionEventPayload.Paused paused) {
            append(value, paused.token().value());
            append(value, paused.reason());
        } else if (payload instanceof TaskExecutionEventPayload.Canceled canceled) {
            append(value, canceled.reason());
        } else if (payload instanceof TaskExecutionEventPayload.Failed failed) {
            appendFailure(value, Optional.of(failed.failure()));
        } else {
            throw new IllegalArgumentException("Unsupported Task runtime event payload");
        }
        return value.toString();
    }

    private AgentRunEventRecorded publicEvent(TaskExecutionEvent event) {
        PublicFields fields = publicFields(event.payload());
        return new AgentRunEventRecorded(
                event.taskExecutionId().value(),
                event.attempt(),
                event.agentRunId().value(),
                event.segmentSequence(),
                event.sequence(),
                eventKind(event.payload()),
                event.occurredAt(),
                fields.safeText,
                fields.name,
                fields.status,
                fields.referenceType,
                fields.referenceId,
                fields.contentHash,
                fields.succeeded,
                fields.progressPercent,
                fields.modelAttempt,
                fields.modelMaxAttempts,
                fields.usage,
                fields.failure);
    }

    private PublicFields publicFields(TaskExecutionEventPayload payload) {
        PublicFields fields = new PublicFields();
        if (payload instanceof TaskExecutionEventPayload.Started started) {
            fields.status = Optional.of(started.segmentKind().name());
        } else if (payload instanceof TaskExecutionEventPayload.TextDelta text) {
            fields.safeText = Optional.of(text.text());
        } else if (payload instanceof TaskExecutionEventPayload.ThinkingSummary thinking) {
            fields.safeText = Optional.of(thinking.summary());
        } else if (payload instanceof TaskExecutionEventPayload.StructuredOutput<?> structured) {
            fields.name = Optional.of(structured.spec().schemaId());
            // Publish only the canonical output hash. The structured value remains private while
            // evaluation and audit can prove which validated result reached the terminal event.
            fields.contentHash = Optional.of(RuntimeContentHash.sha256(
                    json(structured.value())).value());
        } else if (payload instanceof TaskExecutionEventPayload.PlanChanged plan) {
            fields.contentHash = Optional.of(plan.contentHash().value());
            plan.publishedPlanVersionId().ifPresent(id -> fields.reference(
                    "PLAN_VERSION", id.value()));
        } else if (payload instanceof TaskExecutionEventPayload.ToolStarted tool) {
            fields.name = Optional.of(tool.toolName());
        } else if (payload instanceof TaskExecutionEventPayload.ToolResult result) {
            fields.name = Optional.of(result.toolName());
            fields.succeeded = Optional.of(result.succeeded());
            result.artifactId().ifPresent(id -> fields.reference("RUNTIME_ARTIFACT", id.value()));
            fields.failure = result.failure().map(JacksonTaskExecutionEventEncoder::safeFailure);
        } else if (payload instanceof TaskExecutionEventPayload.Progress progress) {
            fields.safeText = Optional.of(progress.safeSummary());
            fields.progressPercent = progress.percent();
        } else if (payload instanceof TaskExecutionEventPayload.ArtifactCreated artifact) {
            fields.name = Optional.of(artifact.kind().name());
            fields.reference("RUNTIME_ARTIFACT", artifact.artifactId().value());
        } else if (payload instanceof TaskExecutionEventPayload.ApprovalRequired approval) {
            fields.safeText = Optional.of(approval.safePrompt());
            fields.status = Optional.of(approval.kind().name());
        } else if (payload instanceof TaskExecutionEventPayload.StatusChanged status) {
            fields.status = Optional.of(status.phase().name());
        } else if (payload instanceof TaskExecutionEventPayload.UsageReported usage) {
            fields.usage = Optional.of(new AgentRunEventRecorded.TokenUsage(
                    usage.inputTokens(), usage.outputTokens(), usage.cachedTokens(), usage.totalTokens()));
        } else if (payload instanceof TaskExecutionEventPayload.ModelTransition transition) {
            fields.name = Optional.of(transition.modelRole().name());
            fields.status = Optional.of(transition.type().name());
            fields.modelAttempt = Optional.of(transition.attempt());
            fields.modelMaxAttempts = Optional.of(transition.maxAttempts());
        } else if (payload instanceof TaskExecutionEventPayload.Completed completed) {
            fields.status = Optional.of("COMPLETED");
            completed.resultArtifactId().ifPresent(id -> fields.reference(
                    "RUNTIME_ARTIFACT", id.value()));
        } else if (payload instanceof TaskExecutionEventPayload.Paused paused) {
            fields.safeText = Optional.of(paused.reason());
            fields.status = Optional.of("PAUSED");
        } else if (payload instanceof TaskExecutionEventPayload.Canceled canceled) {
            fields.safeText = Optional.of(canceled.reason());
            fields.status = Optional.of("CANCELED");
        } else if (payload instanceof TaskExecutionEventPayload.Failed failed) {
            fields.status = Optional.of("FAILED");
            fields.failure = Optional.of(safeFailure(failed.failure()));
        }
        return fields;
    }

    private static AgentRunEventRecorded.SafeFailure safeFailure(ExecutionFailure failure) {
        return new AgentRunEventRecorded.SafeFailure(
                failure.category().name(),
                failure.retryable(),
                failure.safeMessage(),
                failure.runtimeCode());
    }

    private static String eventKind(TaskExecutionEventPayload payload) {
        if (payload instanceof TaskExecutionEventPayload.StructuredOutput<?>) {
            return "STRUCTURED_OUTPUT";
        }
        String name = payload.getClass().getSimpleName();
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Structured runtime event value cannot be fingerprinted", exception);
        }
    }

    private static void appendFailure(
            StringBuilder target, Optional<ExecutionFailure> failure) {
        append(target, failure.isPresent());
        failure.ifPresent(value -> {
            append(target, value.category());
            append(target, value.retryable());
            append(target, value.safeMessage());
            append(target, value.runtimeCode().orElse(""));
        });
    }

    private static void appendUsage(
            StringBuilder target, TaskExecutionEventPayload.UsageReported usage) {
        append(target, usage.inputTokens());
        append(target, usage.outputTokens());
        append(target, usage.cachedTokens());
        append(target, usage.totalTokens());
    }

    private static void append(StringBuilder target, Object value) {
        String text = String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('|');
    }

    private static final class PublicFields {
        private Optional<String> safeText = Optional.empty();
        private Optional<String> name = Optional.empty();
        private Optional<String> status = Optional.empty();
        private Optional<String> referenceType = Optional.empty();
        private Optional<UUID> referenceId = Optional.empty();
        private Optional<String> contentHash = Optional.empty();
        private Optional<Boolean> succeeded = Optional.empty();
        private Optional<Integer> progressPercent = Optional.empty();
        private Optional<Integer> modelAttempt = Optional.empty();
        private Optional<Integer> modelMaxAttempts = Optional.empty();
        private Optional<AgentRunEventRecorded.TokenUsage> usage = Optional.empty();
        private Optional<AgentRunEventRecorded.SafeFailure> failure = Optional.empty();

        private void reference(String type, UUID id) {
            referenceType = Optional.of(type);
            referenceId = Optional.of(id);
        }
    }
}
