package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.CodingCheckpointSequenceConflictException;
import io.crewscope.domain.coding.CodingCheckpointTodo;
import io.crewscope.domain.coding.CodingCheckpointWorkState;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CodingTodoStatus;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentRunSegmentStatus;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CodingCheckpointContractTest {

    private static final UtcTimestamp CAPTURED_AT =
            UtcTimestamp.parse("2026-08-17T12:00:00Z");

    @Test
    void capturesHashClosedRecoveryCoordinatesAndImmutablePlanTodoState() {
        Facts facts = Facts.create();
        CodingCheckpointWorkState state = new CodingCheckpointWorkState(
                "1. Inspect\n2. Change\n3. Test",
                List.of(new CodingCheckpointTodo(
                        "test", CodingTodoStatus.IN_PROGRESS, "Run the bounded test suite")));

        CodingCheckpoint checkpoint = CodingCheckpoint.capture(
                CodingCheckpointId.generate(), facts.target, facts.workspace, facts.policy,
                facts.run, Optional.empty(), state, facts.diffManifest, Optional.empty(),
                facts.snapshot, facts.actor, CAPTURED_AT);

        assertEquals(facts.workspaceId, checkpoint.executionWorkspaceId());
        assertEquals(facts.fingerprint, checkpoint.workspaceFingerprint());
        assertEquals(facts.runId, checkpoint.agentRunId());
        assertEquals(7, checkpoint.snapshotSequence());
        assertEquals(9, checkpoint.checkpointSequence());
        assertEquals(state.contentHash(), checkpoint.workState().contentHash());
        assertTrue(checkpoint.testEvidenceId().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> state.todos().add(new CodingCheckpointTodo(
                        "later", CodingTodoStatus.PENDING, "Later")));
        assertEquals(checkpoint.checkpointHash(), reconstitute(checkpoint).checkpointHash());
    }

    @Test
    void rejectsHashTamperingInvalidSnapshotAndMismatchedRunIdentity() {
        Facts facts = Facts.create();
        CodingCheckpoint checkpoint = CodingCheckpoint.capture(
                CodingCheckpointId.generate(), facts.target, facts.workspace, facts.policy,
                facts.run, Optional.empty(), facts.state(), facts.diffManifest, Optional.empty(),
                facts.snapshot, facts.actor, CAPTURED_AT);

        assertThrows(DomainValidationException.class,
                () -> CodingCheckpoint.reconstitute(
                        checkpoint.id(), checkpoint.scope(), checkpoint.taskId(),
                        checkpoint.taskExecutionId(), checkpoint.attempt(), checkpoint.codingTarget(),
                        checkpoint.executionWorkspaceId(), checkpoint.workspaceFingerprint(),
                        checkpoint.workspacePolicy(), checkpoint.agentRunId(),
                        checkpoint.agentRunSequence(), checkpoint.segmentSequence(),
                        checkpoint.planVersionId(), checkpoint.planVersionHash(),
                        checkpoint.stepExecutionId(), checkpoint.workState(), checkpoint.diffGeneration(),
                        checkpoint.diffManifestHash(), checkpoint.testEvidenceId(),
                        checkpoint.testEvidenceHash(), checkpoint.agentStateSnapshotId(),
                        checkpoint.snapshotSequence(), checkpoint.snapshotContentHash(),
                        checkpoint.checkpointSequence(), TaskFactHash.sha256("forged"),
                        checkpoint.audit()));

        when(facts.snapshot.status()).thenReturn(AgentStateSnapshotStatus.INVALID);
        assertThrows(DomainValidationException.class,
                () -> CodingCheckpoint.capture(
                        CodingCheckpointId.generate(), facts.target, facts.workspace, facts.policy,
                        facts.run, Optional.empty(), facts.state(), facts.diffManifest,
                        Optional.empty(), facts.snapshot, facts.actor, CAPTURED_AT));

        when(facts.snapshot.status()).thenReturn(AgentStateSnapshotStatus.CURRENT);
        when(facts.snapshot.agentRunId()).thenReturn(AgentRunId.generate());
        assertThrows(DomainValidationException.class,
                () -> CodingCheckpoint.capture(
                        CodingCheckpointId.generate(), facts.target, facts.workspace, facts.policy,
                        facts.run, Optional.empty(), facts.state(), facts.diffManifest,
                        Optional.empty(), facts.snapshot, facts.actor, CAPTURED_AT));

        when(facts.snapshot.agentRunId()).thenReturn(facts.runId);
        TestEvidence staleEvidence = mock(TestEvidence.class);
        when(staleEvidence.scope()).thenReturn(facts.scope);
        when(staleEvidence.taskExecutionId()).thenReturn(facts.executionId);
        when(staleEvidence.attempt()).thenReturn(1);
        when(staleEvidence.executionWorkspaceId()).thenReturn(facts.workspaceId);
        when(staleEvidence.workspaceFingerprint()).thenReturn(facts.fingerprint);
        when(staleEvidence.codingTarget()).thenReturn(facts.targetReference);
        when(staleEvidence.workspacePolicy()).thenReturn(facts.policyReference);
        when(staleEvidence.diffGeneration()).thenReturn(facts.diffManifest.generation());
        when(staleEvidence.diffManifestHash())
                .thenReturn(RuntimeContentHash.sha256("stale-tested-diff"));
        when(staleEvidence.id()).thenReturn(TestEvidenceId.generate());
        when(staleEvidence.evidenceHash()).thenReturn(TaskFactHash.sha256("evidence"));
        assertThrows(DomainValidationException.class,
                () -> CodingCheckpoint.capture(
                        CodingCheckpointId.generate(), facts.target, facts.workspace, facts.policy,
                        facts.run, Optional.empty(), facts.state(), facts.diffManifest,
                        Optional.of(staleEvidence), facts.snapshot, facts.actor, CAPTURED_AT));
    }

    @Test
    void exposesStableCheckpointSequenceConflict() {
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();

        CodingCheckpointSequenceConflictException failure =
                new CodingCheckpointSequenceConflictException(workspaceId, 3);

        assertEquals(DomainErrorCode.CODING_CHECKPOINT_SEQUENCE_CONFLICT,
                failure.error().code());
        assertEquals(workspaceId.toString(),
                failure.error().details().get("executionWorkspaceId"));
        assertEquals("3", failure.error().details().get("checkpointSequence"));
        assertThrows(IllegalArgumentException.class,
                () -> new CodingCheckpointSequenceConflictException(workspaceId, 0));
    }

    private static CodingCheckpoint reconstitute(CodingCheckpoint value) {
        return CodingCheckpoint.reconstitute(
                value.id(), value.scope(), value.taskId(), value.taskExecutionId(), value.attempt(),
                value.codingTarget(), value.executionWorkspaceId(), value.workspaceFingerprint(),
                value.workspacePolicy(), value.agentRunId(), value.agentRunSequence(),
                value.segmentSequence(), value.planVersionId(), value.planVersionHash(),
                value.stepExecutionId(), value.workState(), value.diffGeneration(),
                value.diffManifestHash(), value.testEvidenceId(), value.testEvidenceHash(),
                value.agentStateSnapshotId(), value.snapshotSequence(), value.snapshotContentHash(),
                value.checkpointSequence(), value.checkpointHash(), value.audit());
    }

    private static final class Facts {
        final OrganizationId organizationId = OrganizationId.generate();
        final TeamId teamId = TeamId.generate();
        final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        final TaskId taskId = TaskId.generate();
        final TaskExecutionId executionId = TaskExecutionId.generate();
        final CodingTargetSnapshotReference targetReference = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
        final CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
        final ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        final ExecutionWorkspaceFingerprint fingerprint =
                new ExecutionWorkspaceFingerprint(TaskFactHash.sha256("workspace").toString());
        final ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        final WorkspacePolicyReference policyReference = new WorkspacePolicyReference(
                WorkspacePolicyId.generate(), TaskFactHash.sha256("policy"));
        final WorkspacePolicy policy = mock(WorkspacePolicy.class);
        final AgentRunId runId = AgentRunId.generate();
        final AgentRun run = mock(AgentRun.class);
        final AgentStateSnapshot snapshot = mock(AgentStateSnapshot.class);
        final DiffManifest diffManifest = DiffManifest.initial(List.of());
        final Principal actor = mock(Principal.class);

        static Facts create() {
            Facts facts = new Facts();
            when(facts.target.scope()).thenReturn(facts.scope);
            when(facts.target.taskId()).thenReturn(facts.taskId);
            when(facts.target.reference()).thenReturn(facts.targetReference);

            when(facts.workspace.scope()).thenReturn(facts.scope);
            when(facts.workspace.taskId()).thenReturn(facts.taskId);
            when(facts.workspace.taskExecutionId()).thenReturn(facts.executionId);
            when(facts.workspace.attempt()).thenReturn(1);
            when(facts.workspace.codingTarget()).thenReturn(facts.targetReference);
            when(facts.workspace.id()).thenReturn(facts.workspaceId);
            when(facts.workspace.fingerprint()).thenReturn(facts.fingerprint);

            when(facts.policy.scope()).thenReturn(facts.scope);
            when(facts.policy.taskId()).thenReturn(facts.taskId);
            when(facts.policy.taskExecutionId()).thenReturn(facts.executionId);
            when(facts.policy.attempt()).thenReturn(1);
            when(facts.policy.codingTarget()).thenReturn(facts.targetReference);
            when(facts.policy.reference()).thenReturn(facts.policyReference);

            when(facts.run.scope()).thenReturn(facts.scope);
            when(facts.run.taskId()).thenReturn(facts.taskId);
            when(facts.run.executionId()).thenReturn(facts.executionId);
            when(facts.run.id()).thenReturn(facts.runId);
            when(facts.run.runSequence()).thenReturn(2L);
            when(facts.run.stepExecutionId()).thenReturn(Optional.empty());
            when(facts.run.currentSegment()).thenReturn(new AgentRunSegment(
                    3, AgentRunSegmentKind.INVOKE, Optional.empty(),
                    AgentRunSegmentStatus.ACTIVE, CAPTURED_AT, Optional.empty()));

            when(facts.snapshot.scope()).thenReturn(facts.scope);
            when(facts.snapshot.executionId()).thenReturn(facts.executionId);
            when(facts.snapshot.agentRunId()).thenReturn(facts.runId);
            when(facts.snapshot.status()).thenReturn(AgentStateSnapshotStatus.CURRENT);
            when(facts.snapshot.id()).thenReturn(AgentStateSnapshotId.generate());
            when(facts.snapshot.snapshotSequence()).thenReturn(7L);
            when(facts.snapshot.checkpointSequence()).thenReturn(9L);
            when(facts.snapshot.contentHash()).thenReturn(RuntimeContentHash.sha256("agent-state"));

            when(facts.actor.canAct()).thenReturn(true);
            when(facts.actor.id()).thenReturn(PrincipalId.generate());
            when(facts.actor.scope()).thenReturn(PrincipalScope.team(
                    facts.organizationId, facts.teamId));
            return facts;
        }

        CodingCheckpointWorkState state() {
            return new CodingCheckpointWorkState("Inspect and test", List.of());
        }
    }
}
