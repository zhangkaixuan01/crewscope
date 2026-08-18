package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Immutable recovery closure joining Agent state to current workspace, Diff, evidence and plan. */
public final class CodingCheckpoint {

    private final CodingCheckpointId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final CodingTargetSnapshotReference codingTarget;
    private final ExecutionWorkspaceId executionWorkspaceId;
    private final ExecutionWorkspaceFingerprint workspaceFingerprint;
    private final WorkspacePolicyReference workspacePolicy;
    private final AgentRunId agentRunId;
    private final long agentRunSequence;
    private final long segmentSequence;
    private final Optional<PlanVersionId> planVersionId;
    private final Optional<TaskFactHash> planVersionHash;
    private final Optional<StepExecutionId> stepExecutionId;
    private final CodingCheckpointWorkState workState;
    private final DiffGeneration diffGeneration;
    private final RuntimeContentHash diffManifestHash;
    private final Optional<TestEvidenceId> testEvidenceId;
    private final Optional<TaskFactHash> testEvidenceHash;
    private final AgentStateSnapshotId agentStateSnapshotId;
    private final long snapshotSequence;
    private final RuntimeContentHash snapshotContentHash;
    private final long checkpointSequence;
    private final TaskFactHash checkpointHash;
    private final AuditMetadata audit;

    private CodingCheckpoint(
            CodingCheckpointId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            CodingTargetSnapshotReference codingTarget,
            ExecutionWorkspaceId executionWorkspaceId,
            ExecutionWorkspaceFingerprint workspaceFingerprint,
            WorkspacePolicyReference workspacePolicy,
            AgentRunId agentRunId,
            long agentRunSequence,
            long segmentSequence,
            Optional<PlanVersionId> planVersionId,
            Optional<TaskFactHash> planVersionHash,
            Optional<StepExecutionId> stepExecutionId,
            CodingCheckpointWorkState workState,
            DiffGeneration diffGeneration,
            RuntimeContentHash diffManifestHash,
            Optional<TestEvidenceId> testEvidenceId,
            Optional<TaskFactHash> testEvidenceHash,
            AgentStateSnapshotId agentStateSnapshotId,
            long snapshotSequence,
            RuntimeContentHash snapshotContentHash,
            long checkpointSequence,
            Optional<TaskFactHash> expectedHash,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || agentRunSequence < 1 || segmentSequence < 1
                || snapshotSequence < 1 || checkpointSequence < 1) {
            throw invalid("codingCheckpoint.sequence", "all execution sequences must be positive");
        }
        this.attempt = attempt;
        this.codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        this.executionWorkspaceId = Objects.requireNonNull(executionWorkspaceId, "executionWorkspaceId");
        this.workspaceFingerprint = Objects.requireNonNull(workspaceFingerprint, "workspaceFingerprint");
        this.workspacePolicy = Objects.requireNonNull(workspacePolicy, "workspacePolicy");
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.agentRunSequence = agentRunSequence;
        this.segmentSequence = segmentSequence;
        this.planVersionId = Objects.requireNonNull(planVersionId, "planVersionId");
        this.planVersionHash = Objects.requireNonNull(planVersionHash, "planVersionHash");
        if (this.planVersionId.isPresent() != this.planVersionHash.isPresent()) {
            throw invalid("codingCheckpoint.planVersion", "ID and Hash must be present together");
        }
        this.stepExecutionId = Objects.requireNonNull(stepExecutionId, "stepExecutionId");
        this.workState = Objects.requireNonNull(workState, "workState");
        this.diffGeneration = Objects.requireNonNull(diffGeneration, "diffGeneration");
        this.diffManifestHash = Objects.requireNonNull(diffManifestHash, "diffManifestHash");
        this.testEvidenceId = Objects.requireNonNull(testEvidenceId, "testEvidenceId");
        this.testEvidenceHash = Objects.requireNonNull(testEvidenceHash, "testEvidenceHash");
        if (this.testEvidenceId.isPresent() != this.testEvidenceHash.isPresent()) {
            throw invalid("codingCheckpoint.testEvidence", "ID and Hash must be present together");
        }
        this.agentStateSnapshotId = Objects.requireNonNull(agentStateSnapshotId, "agentStateSnapshotId");
        this.snapshotSequence = snapshotSequence;
        this.snapshotContentHash = Objects.requireNonNull(snapshotContentHash, "snapshotContentHash");
        this.checkpointSequence = checkpointSequence;
        this.audit = Objects.requireNonNull(audit, "audit");
        TaskFactHash calculated = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(calculated)) {
                throw invalid("codingCheckpoint.checkpointHash", "must match canonical checkpoint facts");
            }
        });
        this.checkpointHash = calculated;
    }

    /** Captures only already-closed authority facts immediately before state handoff/compaction. */
    public static CodingCheckpoint capture(
            CodingCheckpointId id,
            CodingTargetSnapshot target,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            AgentRun run,
            Optional<PlanVersion> planVersion,
            CodingCheckpointWorkState workState,
            DiffManifest diffManifest,
            Optional<TestEvidence> testEvidence,
            AgentStateSnapshot snapshot,
            Principal actor,
            UtcTimestamp occurredAt) {
        CodingTargetSnapshot requiredTarget = Objects.requireNonNull(target, "target");
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        AgentRun requiredRun = Objects.requireNonNull(run, "run");
        Optional<PlanVersion> requiredPlan = Objects.requireNonNull(planVersion, "planVersion");
        DiffManifest requiredDiff = Objects.requireNonNull(diffManifest, "diffManifest");
        Optional<TestEvidence> requiredEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
        AgentStateSnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        requireClosedFacts(requiredTarget, requiredWorkspace, requiredPolicy, requiredRun,
                requiredPlan, requiredDiff, requiredEvidence, requiredSnapshot);
        PrincipalId actorId = requireActor(actor, requiredWorkspace.scope());
        return new CodingCheckpoint(
                id,
                requiredWorkspace.scope(),
                requiredWorkspace.taskId(),
                requiredWorkspace.taskExecutionId(),
                requiredWorkspace.attempt(),
                requiredTarget.reference(),
                requiredWorkspace.id(),
                requiredWorkspace.fingerprint(),
                requiredPolicy.reference(),
                requiredRun.id(),
                requiredRun.runSequence(),
                requiredRun.currentSegment().sequence(),
                requiredPlan.map(PlanVersion::id),
                requiredPlan.map(PlanVersion::versionHash),
                requiredRun.stepExecutionId(),
                workState,
                requiredDiff.generation(),
                requiredDiff.contentHash(),
                requiredEvidence.map(TestEvidence::id),
                requiredEvidence.map(TestEvidence::evidenceHash),
                requiredSnapshot.id(),
                requiredSnapshot.snapshotSequence(),
                requiredSnapshot.contentHash(),
                requiredSnapshot.checkpointSequence(),
                Optional.empty(),
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    /** Reconstitutes persisted metadata and rejects any altered canonical field. */
    public static CodingCheckpoint reconstitute(
            CodingCheckpointId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            CodingTargetSnapshotReference codingTarget,
            ExecutionWorkspaceId executionWorkspaceId,
            ExecutionWorkspaceFingerprint workspaceFingerprint,
            WorkspacePolicyReference workspacePolicy,
            AgentRunId agentRunId,
            long agentRunSequence,
            long segmentSequence,
            Optional<PlanVersionId> planVersionId,
            Optional<TaskFactHash> planVersionHash,
            Optional<StepExecutionId> stepExecutionId,
            CodingCheckpointWorkState workState,
            DiffGeneration diffGeneration,
            RuntimeContentHash diffManifestHash,
            Optional<TestEvidenceId> testEvidenceId,
            Optional<TaskFactHash> testEvidenceHash,
            AgentStateSnapshotId agentStateSnapshotId,
            long snapshotSequence,
            RuntimeContentHash snapshotContentHash,
            long checkpointSequence,
            TaskFactHash checkpointHash,
            AuditMetadata audit) {
        return new CodingCheckpoint(id, scope, taskId, taskExecutionId, attempt, codingTarget,
                executionWorkspaceId, workspaceFingerprint, workspacePolicy, agentRunId,
                agentRunSequence, segmentSequence, planVersionId, planVersionHash, stepExecutionId,
                workState, diffGeneration, diffManifestHash, testEvidenceId, testEvidenceHash,
                agentStateSnapshotId, snapshotSequence, snapshotContentHash, checkpointSequence,
                Optional.of(Objects.requireNonNull(checkpointHash, "checkpointHash")), audit);
    }

    private static void requireClosedFacts(
            CodingTargetSnapshot target,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            AgentRun run,
            Optional<PlanVersion> plan,
            DiffManifest diffManifest,
            Optional<TestEvidence> evidence,
            AgentStateSnapshot snapshot) {
        if (!workspace.scope().equals(target.scope())
                || !workspace.taskId().equals(target.taskId())
                || !workspace.codingTarget().equals(target.reference())
                || !policy.scope().equals(workspace.scope())
                || !policy.taskId().equals(workspace.taskId())
                || !policy.taskExecutionId().equals(workspace.taskExecutionId())
                || policy.attempt() != workspace.attempt()
                || !policy.codingTarget().equals(target.reference())
                || !run.scope().equals(workspace.scope())
                || !run.taskId().equals(workspace.taskId())
                || !run.executionId().equals(workspace.taskExecutionId())
                || !snapshot.scope().equals(workspace.scope())
                || !snapshot.executionId().equals(workspace.taskExecutionId())
                || !snapshot.agentRunId().equals(run.id())
                || snapshot.status() != AgentStateSnapshotStatus.CURRENT) {
            throw invalid("codingCheckpoint.identity",
                    "must close over one current target, workspace, policy, run and snapshot");
        }
        plan.ifPresent(value -> {
            if (!value.scope().equals(workspace.scope())
                    || !value.taskId().equals(workspace.taskId())
                    || !value.executionId().equals(workspace.taskExecutionId())) {
                throw invalid("codingCheckpoint.planVersion", "must belong to the same execution");
            }
        });
        evidence.ifPresent(value -> {
            if (!value.scope().equals(workspace.scope())
                    || !value.taskExecutionId().equals(workspace.taskExecutionId())
                    || value.attempt() != workspace.attempt()
                    || !value.executionWorkspaceId().equals(workspace.id())
                    || !value.workspaceFingerprint().equals(workspace.fingerprint())
                    || !value.codingTarget().equals(target.reference())
                    || !value.workspacePolicy().equals(policy.reference())
                    || !value.diffGeneration().equals(diffManifest.generation())
                    || !value.diffManifestHash().equals(diffManifest.contentHash())) {
                throw invalid("codingCheckpoint.testEvidence",
                        "must belong to the same execution and exact checkpoint Diff facts");
            }
        });
    }

    private static PrincipalId requireActor(Principal actor, WorkItemScope scope) {
        Principal required = Objects.requireNonNull(actor, "actor");
        boolean outsideTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.canAct()
                || !required.scope().organizationId().equals(scope.organizationId())
                || outsideTeam) {
            throw invalid("codingCheckpoint.createdBy", "must be an active Principal in Task scope");
        }
        return required.id();
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("coding-checkpoint-v1");
        append(canonical, id.toString());
        append(canonical, scope.organizationId().toString());
        append(canonical, scope.teamId().toString());
        append(canonical, scope.workspaceId().toString());
        append(canonical, scope.projectId().toString());
        append(canonical, taskId.toString());
        append(canonical, taskExecutionId.toString());
        append(canonical, Integer.toString(attempt));
        append(canonical, codingTarget.snapshotId().toString());
        append(canonical, Long.toString(codingTarget.revision()));
        append(canonical, codingTarget.snapshotHash().toString());
        append(canonical, executionWorkspaceId.toString());
        append(canonical, workspaceFingerprint.toString());
        append(canonical, workspacePolicy.id().toString());
        append(canonical, workspacePolicy.policyHash().toString());
        append(canonical, agentRunId.toString());
        append(canonical, Long.toString(agentRunSequence));
        append(canonical, Long.toString(segmentSequence));
        append(canonical, planVersionId.map(Object::toString).orElse(""));
        append(canonical, planVersionHash.map(Object::toString).orElse(""));
        append(canonical, stepExecutionId.map(Object::toString).orElse(""));
        append(canonical, workState.contentHash().toString());
        append(canonical, diffGeneration.toString());
        append(canonical, diffManifestHash.toString());
        append(canonical, testEvidenceId.map(Object::toString).orElse(""));
        append(canonical, testEvidenceHash.map(Object::toString).orElse(""));
        append(canonical, agentStateSnapshotId.toString());
        append(canonical, Long.toString(snapshotSequence));
        append(canonical, snapshotContentHash.toString());
        append(canonical, Long.toString(checkpointSequence));
        append(canonical, audit.createdBy().map(Object::toString).orElse(""));
        append(canonical, audit.createdAt().toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static DomainValidationException invalid(String field, String message) {
        return new DomainValidationException(field, message);
    }

    public CodingCheckpointId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public CodingTargetSnapshotReference codingTarget() { return codingTarget; }
    public ExecutionWorkspaceId executionWorkspaceId() { return executionWorkspaceId; }
    public ExecutionWorkspaceFingerprint workspaceFingerprint() { return workspaceFingerprint; }
    public WorkspacePolicyReference workspacePolicy() { return workspacePolicy; }
    public AgentRunId agentRunId() { return agentRunId; }
    public long agentRunSequence() { return agentRunSequence; }
    public long segmentSequence() { return segmentSequence; }
    public Optional<PlanVersionId> planVersionId() { return planVersionId; }
    public Optional<TaskFactHash> planVersionHash() { return planVersionHash; }
    public Optional<StepExecutionId> stepExecutionId() { return stepExecutionId; }
    public CodingCheckpointWorkState workState() { return workState; }
    public DiffGeneration diffGeneration() { return diffGeneration; }
    public RuntimeContentHash diffManifestHash() { return diffManifestHash; }
    public Optional<TestEvidenceId> testEvidenceId() { return testEvidenceId; }
    public Optional<TaskFactHash> testEvidenceHash() { return testEvidenceHash; }
    public AgentStateSnapshotId agentStateSnapshotId() { return agentStateSnapshotId; }
    public long snapshotSequence() { return snapshotSequence; }
    public RuntimeContentHash snapshotContentHash() { return snapshotContentHash; }
    public long checkpointSequence() { return checkpointSequence; }
    public TaskFactHash checkpointHash() { return checkpointHash; }
    public AuditMetadata audit() { return audit; }
}
