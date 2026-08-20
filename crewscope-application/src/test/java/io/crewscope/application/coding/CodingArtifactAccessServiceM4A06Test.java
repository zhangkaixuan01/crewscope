package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.artifact.ArtifactByteRange;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Authorization and relational-lineage closure for M4-A06 Artifact content. */
class CodingArtifactAccessServiceM4A06Test {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), projectId);
    private final TaskId taskId = TaskId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final TeamAccessContext context = mock(TeamAccessContext.class);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final DiffArtifactRepository diffs = mock(DiffArtifactRepository.class);
    private final CommandEvidenceRepository commands = mock(CommandEvidenceRepository.class);
    private final TestEvidenceRepository tests = mock(TestEvidenceRepository.class);
    private final CodingArtifactContentPort contentPort = mock(CodingArtifactContentPort.class);
    private final TransactionExecutor transactions = new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    };

    private CodingArtifactAccessService service;

    @BeforeEach
    void setUp() {
        Principal actor = mock(Principal.class);
        when(actor.id()).thenReturn(PrincipalId.generate());
        when(context.actor()).thenReturn(actor);
        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(executionId);
        when(execution.taskId()).thenReturn(taskId);
        when(execution.scope()).thenReturn(scope);
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
        when(executions.findById(organizationId, executionId)).thenReturn(Optional.of(execution));
        service = new CodingArtifactAccessService(
                accessPolicy, tasks, executions, diffs, commands, tests, contentPort, transactions);
    }

    @Test
    void authorizesExactPatchLineageAndResolvesOpenEndedRange() {
        DiffArtifact artifact = mock(DiffArtifact.class);
        PatchArtifactReference reference = mock(PatchArtifactReference.class);
        CodingArtifactContent expected = mock(CodingArtifactContent.class);
        when(artifact.scope()).thenReturn(scope);
        when(artifact.taskId()).thenReturn(taskId);
        when(artifact.taskExecutionId()).thenReturn(executionId);
        when(artifact.patchArtifact()).thenReturn(reference);
        when(reference.sizeBytes()).thenReturn(100L);
        when(diffs.findByTaskExecution(organizationId, teamId, projectId, executionId))
                .thenReturn(Optional.of(artifact));
        when(contentPort.readPatch(
                        eq(artifact), any(ArtifactAccessContext.class),
                        eq(Optional.of(new ArtifactByteRange(90, 100)))))
                .thenReturn(expected);

        CodingArtifactContent result = service.openPatch(
                context, organizationId, teamId, taskId, executionId,
                CodingArtifactRangeSelection.from(90));

        assertSame(expected, result);
        verify(accessPolicy).requireVisibleTeam(context, organizationId, teamId);
        verify(contentPort).readPatch(
                eq(artifact), any(ArtifactAccessContext.class),
                eq(Optional.of(new ArtifactByteRange(90, 100))));
    }

    @Test
    void rejectsCrossTaskCommandEvidenceBeforeOpeningContent() {
        CommandEvidenceId evidenceId = CommandEvidenceId.generate();
        CommandEvidence evidence = mock(CommandEvidence.class);
        when(evidence.scope()).thenReturn(scope);
        when(evidence.taskId()).thenReturn(TaskId.generate());
        when(evidence.taskExecutionId()).thenReturn(executionId);
        when(commands.findById(organizationId, teamId, projectId, evidenceId))
                .thenReturn(Optional.of(evidence));

        assertThrows(AggregateNotFoundException.class, () -> service.openBuildLog(
                context, organizationId, teamId, taskId, executionId, evidenceId,
                CodingArtifactRangeSelection.whole()));

        verifyNoInteractions(contentPort);
    }

    @Test
    void rejectsUnpublishedTestReportWithoutOpeningAnArtifact() {
        TestEvidenceId evidenceId = TestEvidenceId.generate();
        TestEvidence evidence = mock(TestEvidence.class);
        when(evidence.scope()).thenReturn(scope);
        when(evidence.taskId()).thenReturn(taskId);
        when(evidence.taskExecutionId()).thenReturn(executionId);
        when(evidence.testReport()).thenReturn(Optional.empty());
        when(tests.findById(organizationId, teamId, projectId, evidenceId))
                .thenReturn(Optional.of(evidence));

        assertThrows(AggregateNotFoundException.class, () -> service.openTestReport(
                context, organizationId, teamId, taskId, executionId, evidenceId,
                CodingArtifactRangeSelection.whole()));

        verifyNoInteractions(contentPort);
    }

    @Test
    void closesTestEvidenceReferenceAndSuffixRangeBeforeReading() {
        TestEvidenceId evidenceId = TestEvidenceId.generate();
        TestEvidence evidence = mock(TestEvidence.class);
        EvidenceArtifactReference report = mock(EvidenceArtifactReference.class);
        CodingArtifactContent expected = mock(CodingArtifactContent.class);
        when(evidence.scope()).thenReturn(scope);
        when(evidence.taskId()).thenReturn(taskId);
        when(evidence.taskExecutionId()).thenReturn(executionId);
        when(evidence.testReport()).thenReturn(Optional.of(report));
        when(report.sizeBytes()).thenReturn(12L);
        when(tests.findById(organizationId, teamId, projectId, evidenceId))
                .thenReturn(Optional.of(evidence));
        when(contentPort.readTestReport(
                        eq(evidence), any(), eq(Optional.of(new ArtifactByteRange(7, 12)))))
                .thenReturn(expected);

        CodingArtifactContent result = service.openTestReport(
                context, organizationId, teamId, taskId, executionId, evidenceId,
                CodingArtifactRangeSelection.suffix(5));

        assertSame(expected, result);
    }

    @Test
    void failsClosedBeforeTaskAndArtifactQueriesWhenMembershipIsDenied() {
        when(accessPolicy.requireVisibleTeam(context, organizationId, teamId))
                .thenThrow(new PolicyDeniedException("read Coding Artifact"));

        assertThrows(PolicyDeniedException.class, () -> service.openPatch(
                context, organizationId, teamId, taskId, executionId,
                CodingArtifactRangeSelection.whole()));

        verify(tasks, never()).findById(any(), any());
        verifyNoInteractions(diffs, commands, tests, contentPort);
    }

    @Test
    void returnsSafeUnsatisfiedRangeWithKnownSize() {
        CodingArtifactRangeNotSatisfiableException failure = assertThrows(
                CodingArtifactRangeNotSatisfiableException.class,
                () -> CodingArtifactRangeSelection.between(10, 11).resolve(10));

        assertEquals(10, failure.totalSize());
    }

    @Test
    void truncatesAnOversizedEndToTheArtifactBoundary() {
        assertEquals(
                Optional.of(new ArtifactByteRange(8, 10)),
                CodingArtifactRangeSelection.between(8, 50).resolve(10));
        assertEquals(
                Optional.of(new ArtifactByteRange(8, 10)),
                CodingArtifactRangeSelection.between(8, 12).resolve(10));
    }
}
