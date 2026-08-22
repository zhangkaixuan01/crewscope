package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
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
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Fixed M4-Q01 cross-scope and relational-lineage attacks for Coding Artifact content. */
class CodingArtifactAccessServiceM4Q01Test {

    @TestFactory
    Stream<DynamicTest> blocksEveryPatchArtifactCoordinateSubstitutionBeforeContentRead() {
        return List.of(ArtifactCoordinate.values()).stream().map(coordinate -> dynamicTest(
                "ARTIFACT-" + coordinate.name(),
                () -> new Fixture().assertArtifactCoordinateBlocked(coordinate)));
    }

    @TestFactory
    Stream<DynamicTest> blocksTaskAndAttemptCoordinateSubstitutionBeforeArtifactLookup() {
        return List.of(AttemptCoordinate.values()).stream().map(coordinate -> dynamicTest(
                "ATTEMPT-" + coordinate.name(),
                () -> new Fixture().assertAttemptCoordinateBlocked(coordinate)));
    }

    private enum ArtifactCoordinate {
        ORGANIZATION,
        TEAM,
        WORKSPACE,
        WORK_PROJECT,
        TASK,
        TASK_EXECUTION
    }

    private enum AttemptCoordinate {
        REQUESTED_TEAM,
        EXECUTION_TASK,
        EXECUTION_SCOPE
    }

    private static final class Fixture {

        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId,
                teamId,
                WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final TeamAccessContext context = mock(TeamAccessContext.class);
        private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
        private final TaskRepository tasks = mock(TaskRepository.class);
        private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        private final DiffArtifactRepository diffs = mock(DiffArtifactRepository.class);
        private final CommandEvidenceRepository commands = mock(CommandEvidenceRepository.class);
        private final TestEvidenceRepository tests = mock(TestEvidenceRepository.class);
        private final CodingArtifactContentPort content = mock(CodingArtifactContentPort.class);
        private final Task task = mock(Task.class);
        private final TaskExecution execution = mock(TaskExecution.class);
        private final CodingArtifactAccessService service;

        private Fixture() {
            Principal actor = mock(Principal.class);
            when(actor.id()).thenReturn(PrincipalId.generate());
            when(context.actor()).thenReturn(actor);
            when(task.id()).thenReturn(taskId);
            when(task.scope()).thenReturn(scope);
            when(execution.id()).thenReturn(executionId);
            when(execution.taskId()).thenReturn(taskId);
            when(execution.scope()).thenReturn(scope);
            when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
            when(executions.findById(organizationId, executionId))
                    .thenReturn(Optional.of(execution));
            TransactionExecutor transactions = new TransactionExecutor() {
                @Override
                public <T> T required(Supplier<T> operation) {
                    return operation.get();
                }
            };
            service = new CodingArtifactAccessService(
                    accessPolicy, tasks, executions, diffs, commands, tests, content, transactions);
        }

        private void assertArtifactCoordinateBlocked(ArtifactCoordinate coordinate) {
            DiffArtifact artifact = mock(DiffArtifact.class);
            PatchArtifactReference patch = mock(PatchArtifactReference.class);
            when(patch.sizeBytes()).thenReturn(1L);
            when(artifact.patchArtifact()).thenReturn(patch);
            when(artifact.scope()).thenReturn(mutatedScope(coordinate));
            when(artifact.taskId()).thenReturn(
                    coordinate == ArtifactCoordinate.TASK ? TaskId.generate() : taskId);
            when(artifact.taskExecutionId()).thenReturn(
                    coordinate == ArtifactCoordinate.TASK_EXECUTION
                            ? TaskExecutionId.generate()
                            : executionId);
            when(diffs.findByTaskExecution(
                            organizationId, teamId, scope.projectId(), executionId))
                    .thenReturn(Optional.of(artifact));

            assertThrows(AggregateNotFoundException.class, () -> service.openPatch(
                    context,
                    organizationId,
                    teamId,
                    taskId,
                    executionId,
                    CodingArtifactRangeSelection.whole()));

            verifyNoInteractions(content);
        }

        private void assertAttemptCoordinateBlocked(AttemptCoordinate coordinate) {
            TeamId requestedTeam = teamId;
            if (coordinate == AttemptCoordinate.REQUESTED_TEAM) {
                requestedTeam = TeamId.generate();
            } else if (coordinate == AttemptCoordinate.EXECUTION_TASK) {
                when(execution.taskId()).thenReturn(TaskId.generate());
            } else {
                when(execution.scope()).thenReturn(new WorkItemScope(
                        organizationId,
                        teamId,
                        WorkspaceId.generate(),
                        scope.projectId()));
            }

            TeamId attackTeam = requestedTeam;
            assertThrows(AggregateNotFoundException.class, () -> service.openPatch(
                    context,
                    organizationId,
                    attackTeam,
                    taskId,
                    executionId,
                    CodingArtifactRangeSelection.whole()));

            verifyNoInteractions(content);
        }

        private WorkItemScope mutatedScope(ArtifactCoordinate coordinate) {
            return switch (coordinate) {
                case ORGANIZATION -> new WorkItemScope(
                        OrganizationId.generate(), teamId, scope.workspaceId(), scope.projectId());
                case TEAM -> new WorkItemScope(
                        organizationId, TeamId.generate(), scope.workspaceId(), scope.projectId());
                case WORKSPACE -> new WorkItemScope(
                        organizationId, teamId, WorkspaceId.generate(), scope.projectId());
                case WORK_PROJECT -> new WorkItemScope(
                        organizationId, teamId, scope.workspaceId(), WorkProjectId.generate());
                case TASK, TASK_EXECUTION -> scope;
            };
        }
    }
}
