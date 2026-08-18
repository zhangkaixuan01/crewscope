package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactWorkspaceConflictException;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Executable uniqueness and complete-scope contract for the final DiffArtifact adapter. */
class DiffArtifactRepositoryContractTest {

    @Test
    void atomicallyRejectsASecondFinalArtifactForOneWorkspace() {
        InMemoryRepository repository = new InMemoryRepository();
        WorkItemScope scope = scope();
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        DiffArtifact first = artifact(scope, workspaceId, TaskExecutionId.generate());
        DiffArtifact duplicate = artifact(scope, workspaceId, TaskExecutionId.generate());

        repository.create(first);
        DiffArtifactWorkspaceConflictException failure = assertThrows(
                DiffArtifactWorkspaceConflictException.class,
                () -> repository.create(duplicate));

        assertEquals(DomainErrorCode.DIFF_ARTIFACT_WORKSPACE_CONFLICT, failure.error().code());
        assertEquals(workspaceId.toString(), failure.error().details().get("executionWorkspaceId"));
        assertEquals(
                first.id(),
                repository
                        .findByWorkspace(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                workspaceId)
                        .orElseThrow()
                        .id());
    }

    @Test
    void isolatesEveryLookupByOrganizationTeamAndWorkProject() {
        InMemoryRepository repository = new InMemoryRepository();
        WorkItemScope scope = scope();
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        DiffArtifact artifact = repository.create(artifact(scope, workspaceId, executionId));

        assertEquals(
                artifact.id(),
                repository
                        .findById(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                artifact.id())
                        .orElseThrow()
                        .id());
        assertEquals(
                artifact.id(),
                repository
                        .findByTaskExecution(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                executionId)
                        .orElseThrow()
                        .id());
        assertEquals(
                Optional.empty(),
                repository.findById(
                        OrganizationId.generate(),
                        scope.teamId(),
                        scope.projectId(),
                        artifact.id()));
        assertEquals(
                Optional.empty(),
                repository.findByWorkspace(
                        scope.organizationId(),
                        TeamId.generate(),
                        scope.projectId(),
                        workspaceId));
        assertEquals(
                Optional.empty(),
                repository.findByTaskExecution(
                        scope.organizationId(),
                        scope.teamId(),
                        WorkProjectId.generate(),
                        executionId));
    }

    private static DiffArtifact artifact(
            WorkItemScope scope,
            ExecutionWorkspaceId workspaceId,
            TaskExecutionId taskExecutionId) {
        DiffArtifact artifact = mock(DiffArtifact.class);
        when(artifact.id()).thenReturn(DiffArtifactId.generate());
        when(artifact.scope()).thenReturn(scope);
        when(artifact.executionWorkspaceId()).thenReturn(workspaceId);
        when(artifact.taskExecutionId()).thenReturn(taskExecutionId);
        return artifact;
    }

    private static WorkItemScope scope() {
        return new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
    }

    private static final class InMemoryRepository implements DiffArtifactRepository {

        private final List<DiffArtifact> values = new ArrayList<>();

        @Override
        public DiffArtifact create(DiffArtifact artifact) {
            values.stream()
                    .filter(value -> value.executionWorkspaceId()
                            .equals(artifact.executionWorkspaceId()))
                    .findAny()
                    .ifPresent(ignored -> {
                        throw new DiffArtifactWorkspaceConflictException(
                                artifact.executionWorkspaceId());
                    });
            values.add(artifact);
            return artifact;
        }

        @Override
        public Optional<DiffArtifact> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                DiffArtifactId artifactId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.id().equals(artifactId))
                    .findFirst();
        }

        @Override
        public Optional<DiffArtifact> findByWorkspace(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                ExecutionWorkspaceId workspaceId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.executionWorkspaceId().equals(workspaceId))
                    .findFirst();
        }

        @Override
        public Optional<DiffArtifact> findByTaskExecution(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskExecutionId taskExecutionId) {
            return values.stream()
                    .filter(value -> matches(value, organizationId, teamId, workProjectId))
                    .filter(value -> value.taskExecutionId().equals(taskExecutionId))
                    .findFirst();
        }

        private static boolean matches(
                DiffArtifact value,
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId) {
            return value.scope().organizationId().equals(organizationId)
                    && value.scope().teamId().equals(teamId)
                    && value.scope().projectId().equals(workProjectId);
        }
    }
}
