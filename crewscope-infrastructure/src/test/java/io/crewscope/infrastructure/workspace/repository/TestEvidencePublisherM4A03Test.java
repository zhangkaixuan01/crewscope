package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M4-A03 proof for command-to-Diff/TestEvidence publication. */
class TestEvidencePublisherM4A03Test {

    private static final UtcTimestamp FINISHED =
            UtcTimestamp.parse("2026-08-20T00:00:01Z");

    @Test
    void publishesSuccessfulEvidenceFromParsedVerificationAndCurrentDiff() {
        Fixture fixture = new Fixture();
        TestEvidence published = fixture.publisher.publish(
                        fixture.execution,
                        fixture.actor,
                        fixture.command,
                        new SandboxCommandExecution(
                                fixture.commandSpec,
                                UtcTimestamp.parse("2026-08-20T00:00:00Z"),
                                FINISHED,
                                io.crewscope.domain.coding.CommandTermination.EXITED,
                                Optional.of(0),
                                "[INFO] Results:\n[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1",
                                "",
                                false))
                .orElseThrow();

        assertTrue(published.succeeded());
        assertEquals(3, published.statistics().total());
        assertEquals(2, published.statistics().passed());
        assertEquals(fixture.manifest.contentHash(), published.diffManifestHash());
        assertEquals(List.of(fixture.command.reference()), published.commands());
        assertTrue(published.acceptanceResults().stream()
                .allMatch(result -> result.status()
                        == io.crewscope.domain.coding.AcceptanceStatus.PASSED));
    }

    @Test
    void uncertainCommitRetryReturnsTheExistingCommandEvidenceWithoutDuplicatePublication() {
        Fixture fixture = new Fixture();
        TestEvidence committed = mock(TestEvidence.class);
        CommandEvidenceReference commandReference = fixture.command.reference();
        when(committed.commands()).thenReturn(List.of(commandReference));
        when(fixture.tests.findByWorkspace(
                        fixture.scope.organizationId(),
                        fixture.scope.teamId(),
                        fixture.scope.projectId(),
                        fixture.workspaceId))
                .thenReturn(List.of(committed));

        TestEvidence replay = fixture.publisher.publish(
                        fixture.execution,
                        fixture.actor,
                        fixture.command,
                        fixture.observed())
                .orElseThrow();

        assertEquals(committed, replay);
        verify(fixture.tests, never()).create(any());
        verify(fixture.reports, never()).write(any(), any(), any(), any(), any());
        verify(fixture.execution.diffMonitor().orElseThrow(), never()).reconcileNow();
    }

    private static final class Fixture {
        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        private final ExecutionWorkspaceFingerprint fingerprint =
                new ExecutionWorkspaceFingerprint("f".repeat(64));
        private final io.crewscope.domain.coding.CodingTargetSnapshotReference targetReference =
                new io.crewscope.domain.coding.CodingTargetSnapshotReference(
                        io.crewscope.domain.coding.CodingTargetSnapshotId.generate(),
                        1,
                        TaskFactHash.sha256("target"));
        private final WorkspacePolicyReference policyReference = new WorkspacePolicyReference(
                WorkspacePolicyId.generate(), TaskFactHash.sha256("policy"));
        private final ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        private final io.crewscope.domain.coding.CodingTargetSnapshot target =
                mock(io.crewscope.domain.coding.CodingTargetSnapshot.class);
        private final WorkspacePolicy policy = mock(WorkspacePolicy.class);
        private final Principal actor = mock(Principal.class);
        private final CommandSpec commandSpec = mock(CommandSpec.class);
        private final CommandEvidence command = mock(CommandEvidence.class);
        private final DiffManifest manifest = DiffManifest.initial(List.of());
        private final CodingWorkspaceExecution execution;
        private final TestEvidenceRepository tests = mock(TestEvidenceRepository.class);
        private final TestReportArtifactWriter reports = mock(TestReportArtifactWriter.class);
        private final TestEvidencePublisher publisher;

        private Fixture() {
            when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.ACTIVE);
            when(workspace.scope()).thenReturn(scope);
            when(workspace.taskId()).thenReturn(taskId);
            when(workspace.taskExecutionId()).thenReturn(executionId);
            when(workspace.attempt()).thenReturn(1);
            when(workspace.id()).thenReturn(workspaceId);
            when(workspace.fingerprint()).thenReturn(fingerprint);
            when(workspace.codingTarget()).thenReturn(targetReference);

            when(target.scope()).thenReturn(scope);
            when(target.taskId()).thenReturn(taskId);
            when(target.reference()).thenReturn(targetReference);
            when(target.acceptanceCriteria()).thenReturn(List.of("All automated tests pass"));

            when(policy.scope()).thenReturn(scope);
            when(policy.taskId()).thenReturn(taskId);
            when(policy.taskExecutionId()).thenReturn(executionId);
            when(policy.attempt()).thenReturn(1);
            when(policy.codingTarget()).thenReturn(targetReference);
            when(policy.reference()).thenReturn(policyReference);

            when(actor.id()).thenReturn(PrincipalId.generate());
            when(actor.canAct()).thenReturn(true);
            when(actor.scope()).thenReturn(PrincipalScope.team(
                    scope.organizationId(), scope.teamId()));

            when(commandSpec.commandKind()).thenReturn(CommandKind.TEST);
            when(command.scope()).thenReturn(scope);
            when(command.taskId()).thenReturn(taskId);
            when(command.taskExecutionId()).thenReturn(executionId);
            when(command.attempt()).thenReturn(1);
            when(command.executionWorkspaceId()).thenReturn(workspaceId);
            when(command.workspaceFingerprint()).thenReturn(fingerprint);
            when(command.codingTarget()).thenReturn(targetReference);
            when(command.workspacePolicy()).thenReturn(policyReference);
            when(command.commandSpec()).thenReturn(commandSpec);
            when(command.finishedAt()).thenReturn(FINISHED);
            when(command.succeeded()).thenReturn(true);
            CommandEvidenceReference commandReference = new CommandEvidenceReference(
                    CommandEvidenceId.generate(),
                    EvidenceSequence.first(),
                    TaskFactHash.sha256("command"),
                    Optional.empty());
            when(command.reference()).thenReturn(commandReference);

            WorkspaceDiffMonitor monitor = mock(WorkspaceDiffMonitor.class);
            when(monitor.latest()).thenReturn(Optional.of(manifest));
            execution = new CodingWorkspaceExecution(
                    workspace,
                    target,
                    policy,
                    mock(io.crewscope.domain.coding.BuildProfile.class),
                    mock(ManagedRepository.class),
                    mock(ManagedWorktree.class),
                    mock(ManagedTaskExecutionSandbox.class));
            execution.diffMonitor(monitor);

            when(tests.findByWorkspace(
                            scope.organizationId(), scope.teamId(), scope.projectId(), workspaceId))
                    .thenReturn(List.of());
            when(tests.create(any(TestEvidence.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(reports.write(any(), any(), any(), any(), any())).thenReturn(
                    new EvidenceArtifactReference(
                            io.crewscope.domain.shared.id.ArtifactId.generate(),
                            EvidenceArtifactKind.TEST_REPORT,
                            "text/plain;charset=utf-8",
                            100,
                            RuntimeContentHash.sha256("report")));
            io.crewscope.application.transaction.TransactionExecutor transactions =
                    mock(io.crewscope.application.transaction.TransactionExecutor.class);
            when(transactions.required(any())).thenAnswer(invocation ->
                    ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
            publisher = new TestEvidencePublisher(
                    tests,
                    reports,
                    Clock.fixed(Instant.parse("2026-08-20T00:00:02Z"), ZoneOffset.UTC),
                    io.crewscope.application.coding.CodingTaskTimelinePublisher.NO_OP,
                    transactions);
        }

        private SandboxCommandExecution observed() {
            return new SandboxCommandExecution(
                    commandSpec,
                    UtcTimestamp.parse("2026-08-20T00:00:00Z"),
                    FINISHED,
                    io.crewscope.domain.coding.CommandTermination.EXITED,
                    Optional.of(0),
                    "[INFO] Results:\n[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1",
                    "",
                    false);
        }
    }
}
