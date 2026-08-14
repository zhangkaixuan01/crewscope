package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PostgreSQL metadata for immutable bytes stored by ArtifactStore.
 *
 * <p>No payload, model output, Tool result or AgentState JSON is accepted by this type.
 */
public final class RuntimeArtifact {

    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(?:;[a-z0-9!#$&^_.+\\-=]+)*");

    private final RuntimeArtifactId id;
    private final ArtifactId artifactId;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final Optional<StepExecutionId> stepExecutionId;
    private final AgentRunId agentRunId;
    private final RuntimeArtifactKind kind;
    private final String contentType;
    private final long size;
    private final RuntimeContentHash contentHash;
    private final Optional<UtcTimestamp> retentionUntil;
    private final AuditMetadata audit;

    private RuntimeArtifact(
            RuntimeArtifactId id,
            ArtifactId artifactId,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            AgentRunId agentRunId,
            RuntimeArtifactKind kind,
            String contentType,
            long size,
            RuntimeContentHash contentHash,
            Optional<UtcTimestamp> retentionUntil,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.stepExecutionId = Objects.requireNonNull(stepExecutionId, "stepExecutionId");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.contentType = requireContentType(contentType);
        if (size < 0) {
            throw new DomainValidationException("runtimeArtifact.size", "must not be negative");
        }
        this.size = size;
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.retentionUntil = Objects.requireNonNull(retentionUntil, "retentionUntil");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.retentionUntil.ifPresent(value -> {
            if (value.compareTo(this.audit.createdAt()) <= 0) {
                throw new DomainValidationException(
                        "runtimeArtifact.retentionUntil", "must be after createdAt");
            }
        });
    }

    /** Registers metadata only after ArtifactStore has atomically published and verified bytes. */
    public static RuntimeArtifact register(
            RuntimeArtifactId id,
            ArtifactId artifactId,
            AgentRun run,
            RuntimeArtifactKind kind,
            String contentType,
            long size,
            RuntimeContentHash contentHash,
            Optional<UtcTimestamp> retentionUntil,
            Principal producer,
            UtcTimestamp occurredAt) {
        AgentRun requiredRun = Objects.requireNonNull(run, "run");
        PrincipalId producerId = TaskActorPolicy.requireActiveInScope(
                producer, requiredRun.scope(), "runtimeArtifact.createdBy");
        if (!producerId.equals(requiredRun.agentPrincipalId())) {
            throw new DomainValidationException(
                    "runtimeArtifact.createdBy", "must be the bound AgentRun Principal");
        }
        return new RuntimeArtifact(
                id,
                artifactId,
                requiredRun.scope(),
                requiredRun.taskId(),
                requiredRun.executionId(),
                requiredRun.stepExecutionId(),
                requiredRun.id(),
                kind,
                contentType,
                size,
                contentHash,
                retentionUntil,
                AuditMetadata.createdBy(producerId, occurredAt));
    }

    public static RuntimeArtifact reconstitute(
            RuntimeArtifactId id,
            ArtifactId artifactId,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            AgentRunId agentRunId,
            RuntimeArtifactKind kind,
            String contentType,
            long size,
            RuntimeContentHash contentHash,
            Optional<UtcTimestamp> retentionUntil,
            AuditMetadata audit) {
        return new RuntimeArtifact(
                id,
                artifactId,
                scope,
                taskId,
                executionId,
                stepExecutionId,
                agentRunId,
                kind,
                contentType,
                size,
                contentHash,
                retentionUntil,
                audit);
    }

    public RuntimeArtifactId id() {
        return id;
    }

    public ArtifactId artifactId() {
        return artifactId;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskId taskId() {
        return taskId;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public Optional<StepExecutionId> stepExecutionId() {
        return stepExecutionId;
    }

    public AgentRunId agentRunId() {
        return agentRunId;
    }

    public RuntimeArtifactKind kind() {
        return kind;
    }

    public String contentType() {
        return contentType;
    }

    public long size() {
        return size;
    }

    public RuntimeContentHash contentHash() {
        return contentHash;
    }

    public Optional<UtcTimestamp> retentionUntil() {
        return retentionUntil;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static String requireContentType(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("runtimeArtifact.contentType", "must not be blank");
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (normalized.length() > 255 || !CONTENT_TYPE.matcher(normalized).matches()) {
            throw new DomainValidationException(
                    "runtimeArtifact.contentType", "must be a bounded canonical media type");
        }
        return normalized;
    }
}
