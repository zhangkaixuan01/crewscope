package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceSequenceConflictException;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestEvidenceSequenceConflictException;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Executable uniqueness, complete-scope and ordering contract for evidence adapters. */
class EvidenceRepositoryContractTest {

    @Test
    void commandRepositoryRejectsDuplicateWorkspaceSequenceAndReturnsStableOrder() {
        CommandRepository repository = new CommandRepository();
        WorkItemScope scope = scope();
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        CommandEvidence second = command(scope, workspaceId, executionId, 2);
        CommandEvidence first = command(scope, workspaceId, executionId, 1);

        repository.create(second);
        repository.create(first);
        CommandEvidenceSequenceConflictException failure = assertThrows(
                CommandEvidenceSequenceConflictException.class,
                () -> repository.create(command(scope, workspaceId, executionId, 1)));

        assertEquals(
                DomainErrorCode.COMMAND_EVIDENCE_SEQUENCE_CONFLICT,
                failure.error().code());
        assertEquals(
                List.of(first.id(), second.id()),
                repository
                        .findByWorkspace(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                workspaceId)
                        .stream()
                        .map(CommandEvidence::id)
                        .toList());
        assertEquals(
                List.of(first.id(), second.id()),
                repository
                        .findByTaskExecution(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                executionId)
                        .stream()
                        .map(CommandEvidence::id)
                        .toList());
    }

    @Test
    void commandRepositoryIsolatesEveryLookupByCompleteWorkProjectScope() {
        CommandRepository repository = new CommandRepository();
        WorkItemScope scope = scope();
        CommandEvidence evidence = repository.create(command(
                scope, ExecutionWorkspaceId.generate(), TaskExecutionId.generate(), 1));

        assertEquals(
                evidence.id(),
                repository
                        .findById(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                evidence.id())
                        .orElseThrow()
                        .id());
        assertEquals(
                Optional.empty(),
                repository.findById(
                        OrganizationId.generate(),
                        scope.teamId(),
                        scope.projectId(),
                        evidence.id()));
        assertEquals(
                List.of(),
                repository.findByWorkspace(
                        scope.organizationId(),
                        TeamId.generate(),
                        scope.projectId(),
                        evidence.executionWorkspaceId()));
        assertEquals(
                List.of(),
                repository.findByTaskExecution(
                        scope.organizationId(),
                        scope.teamId(),
                        WorkProjectId.generate(),
                        evidence.taskExecutionId()));
    }

    @Test
    void testRepositoryRejectsDuplicateWorkspaceSequenceAndReturnsStableOrder() {
        TestRepository repository = new TestRepository();
        WorkItemScope scope = scope();
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        TestEvidence third = test(scope, workspaceId, executionId, 3);
        TestEvidence first = test(scope, workspaceId, executionId, 1);

        repository.create(third);
        repository.create(first);
        TestEvidenceSequenceConflictException failure = assertThrows(
                TestEvidenceSequenceConflictException.class,
                () -> repository.create(test(scope, workspaceId, executionId, 3)));

        assertEquals(
                DomainErrorCode.TEST_EVIDENCE_SEQUENCE_CONFLICT,
                failure.error().code());
        assertEquals(
                List.of(first.id(), third.id()),
                repository
                        .findByWorkspace(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                workspaceId)
                        .stream()
                        .map(TestEvidence::id)
                        .toList());
        assertEquals(
                first.id(),
                repository
                        .findById(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                first.id())
                        .orElseThrow()
                        .id());
    }

    @Test
    void testRepositoryIsolatesListsByOrganizationTeamAndWorkProject() {
        TestRepository repository = new TestRepository();
        WorkItemScope scope = scope();
        TestEvidence evidence = repository.create(test(
                scope, ExecutionWorkspaceId.generate(), TaskExecutionId.generate(), 1));

        assertEquals(
                List.of(evidence.id()),
                repository
                        .findByTaskExecution(
                                scope.organizationId(),
                                scope.teamId(),
                                scope.projectId(),
                                evidence.taskExecutionId())
                        .stream()
                        .map(TestEvidence::id)
                        .toList());
        assertEquals(
                Optional.empty(),
                repository.findById(
                        scope.organizationId(),
                        TeamId.generate(),
                        scope.projectId(),
                        evidence.id()));
        assertEquals(
                List.of(),
                repository.findByWorkspace(
                        scope.organizationId(),
                        scope.teamId(),
                        WorkProjectId.generate(),
                        evidence.executionWorkspaceId()));
    }

    private static CommandEvidence command(
            WorkItemScope scope,
            ExecutionWorkspaceId workspaceId,
            TaskExecutionId executionId,
            long sequence) {
        CommandEvidence evidence = mock(CommandEvidence.class);
        when(evidence.id()).thenReturn(CommandEvidenceId.generate());
        when(evidence.scope()).thenReturn(scope);
        when(evidence.executionWorkspaceId()).thenReturn(workspaceId);
        when(evidence.taskExecutionId()).thenReturn(executionId);
        when(evidence.sequence()).thenReturn(new EvidenceSequence(sequence));
        return evidence;
    }

    private static TestEvidence test(
            WorkItemScope scope,
            ExecutionWorkspaceId workspaceId,
            TaskExecutionId executionId,
            long sequence) {
        TestEvidence evidence = mock(TestEvidence.class);
        when(evidence.id()).thenReturn(TestEvidenceId.generate());
        when(evidence.scope()).thenReturn(scope);
        when(evidence.executionWorkspaceId()).thenReturn(workspaceId);
        when(evidence.taskExecutionId()).thenReturn(executionId);
        when(evidence.sequence()).thenReturn(new EvidenceSequence(sequence));
        return evidence;
    }

    private static WorkItemScope scope() {
        return new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
    }

    private static boolean matches(
            WorkItemScope scope,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId) {
        return scope.organizationId().equals(organizationId)
                && scope.teamId().equals(teamId)
                && scope.projectId().equals(workProjectId);
    }

    private static final class CommandRepository implements CommandEvidenceRepository {

        private final List<CommandEvidence> values = new ArrayList<>();

        @Override
        public CommandEvidence create(CommandEvidence evidence) {
            values.stream()
                    .filter(value -> value.executionWorkspaceId()
                            .equals(evidence.executionWorkspaceId()))
                    .filter(value -> value.sequence().equals(evidence.sequence()))
                    .findAny()
                    .ifPresent(ignored -> {
                        throw new CommandEvidenceSequenceConflictException(
                                evidence.executionWorkspaceId(), evidence.sequence());
                    });
            values.add(evidence);
            return evidence;
        }

        @Override
        public Optional<CommandEvidence> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                CommandEvidenceId evidenceId) {
            return values.stream()
                    .filter(value -> matches(
                            value.scope(), organizationId, teamId, workProjectId))
                    .filter(value -> value.id().equals(evidenceId))
                    .findFirst();
        }

        @Override
        public List<CommandEvidence> findByTaskExecution(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskExecutionId taskExecutionId) {
            return values.stream()
                    .filter(value -> matches(
                            value.scope(), organizationId, teamId, workProjectId))
                    .filter(value -> value.taskExecutionId().equals(taskExecutionId))
                    .sorted(Comparator.comparing(CommandEvidence::sequence))
                    .toList();
        }

        @Override
        public List<CommandEvidence> findByWorkspace(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                ExecutionWorkspaceId workspaceId) {
            return values.stream()
                    .filter(value -> matches(
                            value.scope(), organizationId, teamId, workProjectId))
                    .filter(value -> value.executionWorkspaceId().equals(workspaceId))
                    .sorted(Comparator.comparing(CommandEvidence::sequence))
                    .toList();
        }
    }

    private static final class TestRepository implements TestEvidenceRepository {

        private final List<TestEvidence> values = new ArrayList<>();

        @Override
        public TestEvidence create(TestEvidence evidence) {
            values.stream()
                    .filter(value -> value.executionWorkspaceId()
                            .equals(evidence.executionWorkspaceId()))
                    .filter(value -> value.sequence().equals(evidence.sequence()))
                    .findAny()
                    .ifPresent(ignored -> {
                        throw new TestEvidenceSequenceConflictException(
                                evidence.executionWorkspaceId(), evidence.sequence());
                    });
            values.add(evidence);
            return evidence;
        }

        @Override
        public Optional<TestEvidence> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TestEvidenceId evidenceId) {
            return values.stream()
                    .filter(value -> matches(
                            value.scope(), organizationId, teamId, workProjectId))
                    .filter(value -> value.id().equals(evidenceId))
                    .findFirst();
        }

        @Override
        public List<TestEvidence> findByTaskExecution(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                TaskExecutionId taskExecutionId) {
            return values.stream()
                    .filter(value -> matches(
                            value.scope(), organizationId, teamId, workProjectId))
                    .filter(value -> value.taskExecutionId().equals(taskExecutionId))
                    .sorted(Comparator.comparing(TestEvidence::sequence))
                    .toList();
        }

        @Override
        public List<TestEvidence> findByWorkspace(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                ExecutionWorkspaceId workspaceId) {
            return values.stream()
                    .filter(value -> matches(
                            value.scope(), organizationId, teamId, workProjectId))
                    .filter(value -> value.executionWorkspaceId().equals(workspaceId))
                    .sorted(Comparator.comparing(TestEvidence::sequence))
                    .toList();
        }
    }
}
