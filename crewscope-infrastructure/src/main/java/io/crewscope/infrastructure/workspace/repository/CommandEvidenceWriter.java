package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;

/** Publishes the command log first and then the immutable platform-observed evidence fact. */
final class CommandEvidenceWriter {

    private final CommandEvidenceRepository repository;
    private final CommandLogArtifactWriter commandLogs;
    private final Clock clock;

    CommandEvidenceWriter(
            CommandEvidenceRepository repository,
            CommandLogArtifactWriter commandLogs,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.commandLogs = Objects.requireNonNull(commandLogs, "commandLogs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CommandEvidence write(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Principal actor,
            EvidenceSequence sequence,
            SandboxCommandExecution execution) {
        requirePublicationContext(workspace, policy, actor, sequence, execution);
        try {
            EvidenceArtifactReference log = commandLogs.write(workspace, actor, sequence, execution);
            UtcTimestamp recordedAt = UtcTimestamp.from(clock.instant());
            if (recordedAt.compareTo(execution.finishedAt()) < 0) {
                recordedAt = execution.finishedAt();
            }
            CommandEvidence evidence = CommandEvidence.record(
                    CommandEvidenceId.generate(),
                    workspace,
                    policy,
                    sequence,
                    execution.commandSpec(),
                    execution.startedAt(),
                    execution.finishedAt(),
                    execution.termination(),
                    execution.exitCode(),
                    summary(execution),
                    log,
                    actor,
                    recordedAt);
            return repository.create(evidence);
        } catch (SandboxCommandException failure) {
            throw failure;
        } catch (RuntimeException publicationFailure) {
            throw new SandboxCommandException(
                    SandboxCommandError.EVIDENCE_PUBLICATION_FAILED,
                    "Command evidence could not be published");
        }
    }

    private static void requirePublicationContext(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            Principal actor,
            EvidenceSequence sequence,
            SandboxCommandExecution execution) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        Principal requiredActor = Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sequence, "sequence");
        SandboxCommandExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        boolean outsideTeam = requiredActor.scope().teamId().isPresent()
                && requiredActor.scope().teamId()
                        .filter(requiredWorkspace.scope().teamId()::equals)
                        .isEmpty();
        if (requiredWorkspace.status() != ExecutionWorkspaceStatus.ACTIVE
                || !requiredWorkspace.scope().equals(requiredPolicy.scope())
                || !requiredWorkspace.taskId().equals(requiredPolicy.taskId())
                || !requiredWorkspace.taskExecutionId().equals(requiredPolicy.taskExecutionId())
                || requiredWorkspace.attempt() != requiredPolicy.attempt()
                || !requiredWorkspace.codingTarget().equals(requiredPolicy.codingTarget())
                || !requiredExecution.commandSpec().workspacePolicy().equals(requiredPolicy.reference())
                || !requiredActor.canAct()
                || !requiredActor.scope()
                        .organizationId()
                        .equals(requiredWorkspace.scope().organizationId())
                || outsideTeam) {
            throw new SandboxCommandException(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Command evidence facts do not match the active Workspace context");
        }
    }

    private static EvidenceSummary summary(SandboxCommandExecution execution) {
        long outputBytes = (long) execution.stdout().getBytes(StandardCharsets.UTF_8).length
                + execution.stderr().getBytes(StandardCharsets.UTF_8).length;
        String value = "kind=" + execution.commandSpec().commandKind().name()
                + " termination=" + execution.termination().name()
                + " exitCode=" + execution.exitCode().map(String::valueOf).orElse("none")
                + " outputBytes=" + outputBytes
                + " outputTruncated=" + execution.outputTruncated();
        return new EvidenceSummary(value);
    }
}
