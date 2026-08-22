package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.artifact.ArtifactDescriptor;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.RuntimeArtifactRepository;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeArtifactKind;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Comparator;
import java.util.Objects;

/** Registers ArtifactStore bytes as relational runtime metadata before Coding facts reference them. */
public final class CodingRuntimeArtifactRegistrar {

    private final RuntimeArtifactRepository artifacts;
    private final AgentRunRepository runs;

    public CodingRuntimeArtifactRegistrar(
            RuntimeArtifactRepository artifacts, AgentRunRepository runs) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    /** Idempotently binds one stored Coding artifact to the latest producing AgentRun. */
    public RuntimeArtifact register(
            ExecutionWorkspace workspace,
            Principal actor,
            RuntimeArtifactKind kind,
            ArtifactDescriptor descriptor) {
        ExecutionWorkspace current = Objects.requireNonNull(workspace, "workspace");
        Principal producer = Objects.requireNonNull(actor, "actor");
        RuntimeArtifactKind requiredKind = Objects.requireNonNull(kind, "kind");
        ArtifactDescriptor stored = Objects.requireNonNull(descriptor, "descriptor");
        AgentRun run = latestProducingRun(current, producer);
        RuntimeArtifact expected = RuntimeArtifact.register(
                RuntimeArtifactId.generate(),
                stored.artifactId(),
                run,
                requiredKind,
                stored.contentType(),
                stored.size(),
                new RuntimeContentHash(stored.sha256().toString()),
                stored.retentionUntil(),
                producer,
                stored.createdAt());
        var existing = artifacts.findByArtifactId(
                current.scope().organizationId(), stored.artifactId());
        if (existing.isPresent()) {
            return requireSame(existing.orElseThrow(), expected);
        }
        try {
            return artifacts.create(expected);
        } catch (RuntimeException raced) {
            return artifacts.findByArtifactId(
                            current.scope().organizationId(), stored.artifactId())
                    .map(value -> requireSame(value, expected))
                    .orElseThrow(() -> raced);
        }
    }

    private AgentRun latestProducingRun(ExecutionWorkspace workspace, Principal actor) {
        return runs.findByExecution(
                        workspace.scope().organizationId(), workspace.taskExecutionId())
                .stream()
                .filter(run -> run.scope().equals(workspace.scope()))
                .filter(run -> run.taskId().equals(workspace.taskId()))
                .filter(run -> run.agentPrincipalId().equals(actor.id()))
                .max(Comparator.comparingLong(AgentRun::runSequence))
                .orElseThrow(() -> new IllegalStateException(
                        "Coding Artifact producer AgentRun is unavailable"));
    }

    private static RuntimeArtifact requireSame(
            RuntimeArtifact existing, RuntimeArtifact expected) {
        if (!existing.artifactId().equals(expected.artifactId())
                || !existing.scope().equals(expected.scope())
                || !existing.taskId().equals(expected.taskId())
                || !existing.executionId().equals(expected.executionId())
                || !existing.agentRunId().equals(expected.agentRunId())
                || existing.kind() != expected.kind()
                || !existing.contentType().equals(expected.contentType())
                || existing.size() != expected.size()
                || !existing.contentHash().equals(expected.contentHash())) {
            throw new IllegalStateException(
                    "Coding Artifact runtime metadata conflicts with stored bytes");
        }
        return existing;
    }
}
