package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentInterruptId;
import io.crewscope.domain.task.AgentInterruptKind;
import io.crewscope.domain.task.AgentInterruptResolution;
import io.crewscope.domain.task.AgentInterruptStatus;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunContinuityGap;
import io.crewscope.domain.task.AgentRunContinuityGapReason;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegment;
import io.crewscope.domain.task.AgentRunStatus;
import io.crewscope.domain.task.AgentRunTerminal;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.AgentStateSnapshotStatus;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PlanChangeReason;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotChangeReason;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import io.crewscope.domain.task.RuntimeArtifactKind;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.SafetyRestriction;
import io.crewscope.domain.task.StepCheckpoint;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.StepWaitReason;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskCredentialGrantStatus;
import io.crewscope.domain.task.TaskCredentialGrantTermination;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskProviderAuthorization;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.task.TaskTokenJtiHash;
import io.crewscope.domain.task.TodoSummaryItem;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Maps the append-only policy/plan facts and mutable M3 execution children. */
@Component
public class TaskRuntimeExtendedPersistenceMapper {

    PolicySnapshotEntity toEntity(PolicySnapshot value) {
        PolicySnapshotEntity row = new PolicySnapshotEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.revision = value.revision();
        row.parentSnapshotId = value.parentSnapshotId().map(PolicySnapshotId::value).orElse(null);
        row.changeReason = value.changeReason().name();
        putExecutionPrincipal(row, value.executionPrincipal());
        row.policyPackId = value.policyPack().id().value();
        row.policyPackVersion = value.policyPack().version();
        row.agentProfileId = value.agentProfileId().value();
        row.agentProfileVersion = value.agentProfileVersion();
        row.capabilities = enumNames(value.capabilities());
        row.allowedTools = sorted(value.allowedTools());
        row.providerBindingIds = value.providerBindingIds().stream()
                .map(id -> id.value().toString()).sorted().toList();
        row.maxTokens = value.budget().maxTokens();
        row.maxModelCalls = value.budget().maxModelCalls();
        row.maxToolCalls = value.budget().maxToolCalls();
        row.maxDurationSeconds = value.budget().maxDurationSeconds();
        row.snapshotHash = value.snapshotHash().value();
        putImmutableAudit(row, value.createdByPrincipalId(), value.createdAt());
        return row;
    }

    PolicySnapshot toDomain(PolicySnapshotEntity row) {
        return PolicySnapshot.reconstitute(
                new PolicySnapshotId(row.id), scope(row), new TaskId(row.taskId),
                new TaskExecutionId(row.taskExecutionId), row.revision,
                optional(row.parentSnapshotId, PolicySnapshotId::new),
                PolicySnapshotChangeReason.valueOf(row.changeReason), executionPrincipal(row),
                new io.crewscope.domain.policy.PolicyPackReference(
                        new io.crewscope.domain.policy.PolicyPackId(row.policyPackId),
                        row.policyPackVersion),
                new AgentProfileId(row.agentProfileId), row.agentProfileVersion,
                enumSet(row.capabilities, ExecutionCapability::valueOf), Set.copyOf(row.allowedTools),
                row.providerBindingIds.stream()
                        .map(UUID::fromString).map(ProviderBindingId::new).collect(Collectors.toSet()),
                new PolicyBudget(row.maxTokens, row.maxModelCalls, row.maxToolCalls,
                        row.maxDurationSeconds),
                new TaskFactHash(row.snapshotHash.trim()), new PrincipalId(row.createdByPrincipalId),
                new UtcTimestamp(row.createdAt));
    }

    SafetyEnforcementOverlayEntity toEntity(SafetyEnforcementOverlay value) {
        SafetyEnforcementOverlayEntity row = new SafetyEnforcementOverlayEntity();
        row.id = value.id().value();
        row.overlayVersion = value.version();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.parentOverlayHash = value.parentOverlayHash().map(TaskFactHash::value).orElse(null);
        row.restrictions = enumNames(value.restrictions());
        row.disabledCapabilities = enumNames(value.disabledCapabilities());
        row.disabledTools = sorted(value.disabledTools());
        row.overlayHash = value.overlayHash().value();
        putImmutableAudit(row, value.createdByPrincipalId(), value.createdAt());
        return row;
    }

    SafetyEnforcementOverlay toDomain(SafetyEnforcementOverlayEntity row) {
        return SafetyEnforcementOverlay.reconstitute(
                new SafetyEnforcementOverlayId(row.id), scope(row), new TaskId(row.taskId),
                new TaskExecutionId(row.taskExecutionId), row.overlayVersion,
                optional(trim(row.parentOverlayHash), TaskFactHash::new),
                enumSet(row.restrictions, SafetyRestriction::valueOf),
                enumSet(row.disabledCapabilities, ExecutionCapability::valueOf),
                Set.copyOf(row.disabledTools), new TaskFactHash(row.overlayHash.trim()),
                new PrincipalId(row.createdByPrincipalId), new UtcTimestamp(row.createdAt));
    }

    PlanVersionEntity toEntity(PlanVersion value) {
        PlanVersionEntity row = new PlanVersionEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.revision = value.revision();
        row.parentVersionId = value.parentVersionId().map(PlanVersionId::value).orElse(null);
        row.changeReason = value.changeReason().name();
        row.policySnapshotId = value.policySnapshotId().value();
        row.policySnapshotHash = value.policySnapshotHash().value();
        putSafetyOverlay(row, value.safetyOverlay());
        putExecutionPrincipal(row, value.executionPrincipal());
        row.markdown = value.markdown();
        row.contentHash = value.contentHash().value();
        row.versionHash = value.versionHash().value();
        row.publishedByPrincipalId = value.publishedByPrincipalId().value();
        row.publishedAt = value.publishedAt().value();
        putImmutableAudit(row, value.publishedByPrincipalId(), value.publishedAt());
        return row;
    }

    PlanVersion toDomain(
            PlanVersionEntity row, List<PlanStep> steps, List<TodoSummaryItem> todos) {
        return PlanVersion.reconstitute(
                new PlanVersionId(row.id), scope(row), new TaskId(row.taskId),
                new TaskExecutionId(row.taskExecutionId), row.revision,
                optional(row.parentVersionId, PlanVersionId::new),
                PlanChangeReason.valueOf(row.changeReason),
                new PolicySnapshotId(row.policySnapshotId),
                new TaskFactHash(row.policySnapshotHash.trim()), safetyOverlay(row),
                executionPrincipal(row), row.markdown, new TaskFactHash(row.contentHash.trim()),
                steps, todos, new TaskFactHash(row.versionHash.trim()),
                new PrincipalId(row.publishedByPrincipalId), new UtcTimestamp(row.publishedAt));
    }

    StepExecutionEntity toEntity(StepExecution value) {
        StepExecutionEntity row = new StepExecutionEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.planVersionId = value.planVersionId().value();
        row.planVersionHash = value.planVersionHash().value();
        row.planStepKey = value.planStepKey();
        row.sequence = value.sequence();
        row.critical = value.critical();
        putExecutionPrincipal(row, value.executionPrincipal());
        row.policySnapshotId = value.policySnapshotId().value();
        row.policySnapshotHash = value.policySnapshotHash().value();
        putSafetyOverlay(row, value.safetyOverlay());
        copyState(row, value);
        putAudit(row, value.audit(), value.version());
        return row;
    }

    void copyState(StepExecutionEntity row, StepExecution value) {
        row.runAttempt = value.runAttempt();
        row.maxRunAttempts = value.maxRunAttempts();
        row.status = value.status().name();
        row.waitReason = value.waitReason().map(Enum::name).orElse(null);
        StepCheckpoint checkpoint = value.checkpoint().orElse(null);
        row.checkpointSequence = checkpoint == null ? null : checkpoint.sequence();
        row.checkpointCode = checkpoint == null ? null : checkpoint.code();
        row.checkpointPayloadHash = checkpoint == null ? null : checkpoint.payloadHash().value();
        row.checkpointRecordedByPrincipalId = checkpoint == null
                ? null : checkpoint.recordedByPrincipalId().value();
        row.checkpointRecordedAt = checkpoint == null ? null : checkpoint.recordedAt().value();
        TaskExecutionFailure failure = value.failure().orElse(null);
        row.failureClass = failure == null ? null : failure.failureClass().name();
        row.failureCode = failure == null ? null : failure.code();
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "step.updatedBy");
    }

    StepExecution toDomain(StepExecutionEntity row) {
        Optional<StepCheckpoint> checkpoint = row.checkpointSequence == null
                ? Optional.empty()
                : Optional.of(new StepCheckpoint(row.checkpointSequence, row.checkpointCode,
                        new TaskFactHash(row.checkpointPayloadHash.trim()),
                        new PrincipalId(row.checkpointRecordedByPrincipalId),
                        new UtcTimestamp(row.checkpointRecordedAt)));
        Optional<TaskExecutionFailure> failure = row.failureClass == null
                ? Optional.empty()
                : Optional.of(new TaskExecutionFailure(
                        TaskExecutionFailureClass.valueOf(row.failureClass), row.failureCode));
        return StepExecution.reconstitute(
                new StepExecutionId(row.id), scope(row), new TaskId(row.taskId),
                new TaskExecutionId(row.taskExecutionId), new PlanVersionId(row.planVersionId),
                new TaskFactHash(row.planVersionHash.trim()), row.planStepKey, row.sequence,
                row.critical, executionPrincipal(row), new PolicySnapshotId(row.policySnapshotId),
                new TaskFactHash(row.policySnapshotHash.trim()), safetyOverlay(row),
                row.runAttempt, row.maxRunAttempts, StepExecutionStatus.valueOf(row.status),
                optional(row.waitReason, StepWaitReason::valueOf), checkpoint, failure,
                row.version, audit(row));
    }

    TaskCredentialGrantEntity toEntity(TaskCredentialGrant value) {
        TaskCredentialGrantEntity row = new TaskCredentialGrantEntity();
        row.id = value.id().value();
        TaskTokenGrantScope scope = value.scope();
        putScope(row, scope.workItemScope());
        row.taskId = scope.taskId().value();
        row.taskExecutionId = scope.taskExecutionId().value();
        row.attempt = scope.attempt();
        row.executionLeaseId = scope.executionLeaseId().value();
        row.runtimeEnvironment = scope.environment().value();
        row.runtimeId = scope.runtimeId().value();
        row.workerId = scope.workerId().value();
        row.claimTokenHash = scope.claimTokenHash().value();
        row.fencingToken = scope.fencingToken().value();
        putExecutionPrincipal(row, scope.executionPrincipal());
        row.policySnapshotId = scope.policySnapshotId().value();
        row.policySnapshotHash = scope.policySnapshotHash().value();
        putSafetyOverlay(row, scope.safetyOverlay());
        row.jtiHash = value.jtiHash().value();
        row.issuedAt = value.issuedAt().value();
        row.expiresAt = value.expiresAt().value();
        copyState(row, value);
        putAudit(row, value.audit(), value.version());
        return row;
    }

    void copyState(TaskCredentialGrantEntity row, TaskCredentialGrant value) {
        row.status = value.status().name();
        row.useCount = value.useCount();
        row.lastUsedAt = value.lastUsedAt().map(UtcTimestamp::value).orElse(null);
        TaskCredentialGrantTermination termination = value.termination().orElse(null);
        row.terminatedByPrincipalId = termination == null
                ? null : termination.terminatedByPrincipalId().value();
        row.terminatedAt = termination == null ? null : termination.terminatedAt().value();
        row.terminationReason = termination == null ? null : termination.reason();
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "grant.updatedBy");
    }

    TaskCredentialGrant toDomain(
            TaskCredentialGrantEntity row,
            Set<String> tools,
            Set<TaskProviderAuthorization> providers) {
        TaskTokenGrantScope grantScope = new TaskTokenGrantScope(
                scope(row), new TaskId(row.taskId), new TaskExecutionId(row.taskExecutionId),
                row.attempt, new ExecutionLeaseId(row.executionLeaseId),
                new RuntimeEnvironment(row.runtimeEnvironment), new ExecutionRuntimeId(row.runtimeId),
                new RuntimeWorkerId(row.workerId), new ClaimTokenHash(row.claimTokenHash.trim()),
                new FencingToken(row.fencingToken), executionPrincipal(row),
                new PolicySnapshotId(row.policySnapshotId),
                new TaskFactHash(row.policySnapshotHash.trim()), safetyOverlay(row), tools, providers);
        Optional<TaskCredentialGrantTermination> terminal = row.terminatedAt == null
                ? Optional.empty()
                : Optional.of(new TaskCredentialGrantTermination(
                        TaskCredentialGrantStatus.valueOf(row.status),
                        new PrincipalId(row.terminatedByPrincipalId),
                        new UtcTimestamp(row.terminatedAt), row.terminationReason));
        return TaskCredentialGrant.reconstitute(
                new TaskCredentialGrantId(row.id), new TaskTokenJtiHash(row.jtiHash.trim()),
                grantScope, new UtcTimestamp(row.issuedAt), new UtcTimestamp(row.expiresAt),
                TaskCredentialGrantStatus.valueOf(row.status), row.useCount,
                optional(row.lastUsedAt, UtcTimestamp::new), terminal, row.version, audit(row));
    }

    TaskAgentRuntimeSessionEntity toEntity(TaskAgentRuntimeSession value) {
        TaskAgentRuntimeSessionEntity row = new TaskAgentRuntimeSessionEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.stepExecutionId = value.stepExecutionId().map(StepExecutionId::value).orElse(null);
        row.sessionPurpose = value.purpose().name();
        row.agentPrincipalId = value.agentPrincipalId().value();
        row.agentProfileId = value.agentProfileId().value();
        row.agentProfileVersion = value.agentProfileVersion();
        row.agentScopeUserId = value.agentScopeKey().userId();
        row.agentScopeSessionId = value.agentScopeKey().sessionId();
        row.stateReference = value.stateReference().value();
        row.status = value.status().name();
        row.agentPrincipalType = value.purpose() == TaskAgentSessionPurpose.SPECIALIST
                ? "SPECIALIST_AGENT" : "TEAM_AGENT";
        row.agentProfileType = value.purpose() == TaskAgentSessionPurpose.SPECIALIST
                ? "SPECIALIST" : "TEAM";
        putAudit(row, value.audit(), value.version());
        return row;
    }

    void copyState(TaskAgentRuntimeSessionEntity row, TaskAgentRuntimeSession value) {
        row.agentProfileVersion = value.agentProfileVersion();
        row.status = value.status().name();
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "session.updatedBy");
    }

    TaskAgentRuntimeSession toDomain(TaskAgentRuntimeSessionEntity row) {
        return TaskAgentRuntimeSession.reconstitute(
                new AgentRuntimeSessionId(row.id), scope(row), new TaskId(row.taskId),
                new TaskExecutionId(row.taskExecutionId),
                optional(row.stepExecutionId, StepExecutionId::new),
                TaskAgentSessionPurpose.valueOf(row.sessionPurpose),
                new PrincipalId(row.agentPrincipalId), new AgentProfileId(row.agentProfileId),
                row.agentProfileVersion,
                new AgentScopeSessionKey(row.agentScopeUserId, row.agentScopeSessionId),
                new AgentRuntimeStateReference(row.stateReference),
                AgentRuntimeSessionStatus.valueOf(row.status), row.version, audit(row));
    }

    AgentRunEntity toEntity(AgentRun value) {
        AgentRunEntity row = new AgentRunEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.stepExecutionId = value.stepExecutionId().map(StepExecutionId::value).orElse(null);
        row.runtimeSessionId = value.runtimeSessionId().value();
        row.agentPrincipalId = value.agentPrincipalId().value();
        row.agentProfileId = value.agentProfileId().value();
        row.agentProfileVersion = value.agentProfileVersion();
        row.runSequence = value.runSequence();
        copyState(row, value);
        putAudit(row, value.audit(), value.version());
        return row;
    }

    void copyState(AgentRunEntity row, AgentRun value) {
        row.status = value.status().name();
        AgentRunContinuityGap gap = value.continuityGap().orElse(null);
        row.continuityPreviousRunId = gap == null ? null : gap.previousRunId().value();
        row.continuitySnapshotId = gap == null
                ? null : gap.lastValidSnapshotId().map(AgentStateSnapshotId::value).orElse(null);
        row.firstMissingCheckpoint = gap == null ? null : gap.firstMissingCheckpoint();
        row.lastMissingCheckpoint = gap == null ? null : gap.lastMissingCheckpoint();
        row.continuityReason = gap == null ? null : gap.reason().name();
        row.continuityDetectedAt = gap == null ? null : gap.detectedAt().value();
        AgentRunTerminal terminal = value.terminal().orElse(null);
        row.terminalFailureCode = terminal == null ? null : terminal.failureCode().orElse(null);
        row.terminalResultArtifactId = terminal == null
                ? null : terminal.resultArtifactId().map(RuntimeArtifactId::value).orElse(null);
        row.terminalAt = terminal == null ? null : terminal.occurredAt().value();
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "agentRun.updatedBy");
    }

    AgentRun toDomain(AgentRunEntity row, List<AgentRunSegment> segments) {
        Optional<AgentRunContinuityGap> gap = row.continuityPreviousRunId == null
                ? Optional.empty()
                : Optional.of(new AgentRunContinuityGap(
                        new AgentRunId(row.continuityPreviousRunId),
                        optional(row.continuitySnapshotId, AgentStateSnapshotId::new),
                        row.firstMissingCheckpoint, row.lastMissingCheckpoint,
                        AgentRunContinuityGapReason.valueOf(row.continuityReason),
                        new UtcTimestamp(row.continuityDetectedAt)));
        Optional<AgentRunTerminal> terminal = row.terminalAt == null
                ? Optional.empty()
                : Optional.of(new AgentRunTerminal(AgentRunStatus.valueOf(row.status),
                        Optional.ofNullable(row.terminalFailureCode),
                        optional(row.terminalResultArtifactId, RuntimeArtifactId::new),
                        new UtcTimestamp(row.terminalAt)));
        return AgentRun.reconstitute(
                new AgentRunId(row.id), scope(row), new TaskId(row.taskId),
                new TaskExecutionId(row.taskExecutionId),
                optional(row.stepExecutionId, StepExecutionId::new),
                new AgentRuntimeSessionId(row.runtimeSessionId),
                new PrincipalId(row.agentPrincipalId), new AgentProfileId(row.agentProfileId),
                row.agentProfileVersion, row.runSequence, AgentRunStatus.valueOf(row.status),
                segments, gap, terminal, row.version, audit(row));
    }

    AgentInterruptEntity toEntity(AgentInterrupt value, UUID taskId) {
        AgentInterruptEntity row = new AgentInterruptEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = taskId;
        row.taskExecutionId = value.executionId().value();
        row.agentRunId = value.agentRunId().value();
        row.segmentSequence = value.segmentSequence();
        row.kind = value.kind().name();
        row.interruptTokenHash = value.interruptTokenHash().value();
        copyState(row, value);
        putAudit(row, value.audit(), value.version());
        return row;
    }

    void copyState(AgentInterruptEntity row, AgentInterrupt value) {
        row.status = value.status().name();
        AgentInterruptResolution resolution = value.resolution().orElse(null);
        row.resumeRequestId = resolution == null ? null : resolution.resumeRequestId();
        row.responseHash = resolution == null ? null : resolution.responseHash().value();
        row.resolvedByPrincipalId = resolution == null ? null : resolution.resolvedBy().value();
        row.resolvedAt = resolution == null ? null : resolution.resolvedAt().value();
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "interrupt.updatedBy");
    }

    AgentInterrupt toDomain(AgentInterruptEntity row) {
        Optional<AgentInterruptResolution> resolution = row.resumeRequestId == null
                ? Optional.empty()
                : Optional.of(new AgentInterruptResolution(row.resumeRequestId,
                        new RuntimeContentHash(row.responseHash.trim()),
                        new PrincipalId(row.resolvedByPrincipalId), new UtcTimestamp(row.resolvedAt)));
        return AgentInterrupt.reconstitute(
                new AgentInterruptId(row.id), scope(row),
                new TaskExecutionId(row.taskExecutionId), new AgentRunId(row.agentRunId),
                row.segmentSequence, AgentInterruptKind.valueOf(row.kind),
                new RuntimeContentHash(row.interruptTokenHash.trim()),
                AgentInterruptStatus.valueOf(row.status), resolution, row.version, audit(row));
    }

    RuntimeArtifactEntity toEntity(RuntimeArtifact value) {
        RuntimeArtifactEntity row = new RuntimeArtifactEntity();
        row.id = value.id().value();
        row.artifactId = value.artifactId().value();
        putScope(row, value.scope());
        row.taskId = value.taskId().value();
        row.taskExecutionId = value.executionId().value();
        row.stepExecutionId = value.stepExecutionId().map(StepExecutionId::value).orElse(null);
        row.agentRunId = value.agentRunId().value();
        row.kind = value.kind().name();
        row.contentType = value.contentType();
        row.sizeBytes = value.size();
        row.contentHash = value.contentHash().value();
        row.retentionUntil = value.retentionUntil().map(UtcTimestamp::value).orElse(null);
        putAudit(row, value.audit(), 0);
        return row;
    }

    RuntimeArtifact toDomain(RuntimeArtifactEntity row) {
        return RuntimeArtifact.reconstitute(
                new RuntimeArtifactId(row.id), new ArtifactId(row.artifactId), scope(row),
                new TaskId(row.taskId), new TaskExecutionId(row.taskExecutionId),
                optional(row.stepExecutionId, StepExecutionId::new), new AgentRunId(row.agentRunId),
                RuntimeArtifactKind.valueOf(row.kind), row.contentType, row.sizeBytes,
                new RuntimeContentHash(row.contentHash.trim()),
                optional(row.retentionUntil, UtcTimestamp::new), audit(row));
    }

    AgentStateSnapshotEntity toEntity(AgentStateSnapshot value, UUID taskId) {
        AgentStateSnapshotEntity row = new AgentStateSnapshotEntity();
        row.id = value.id().value();
        putScope(row, value.scope());
        row.taskId = taskId;
        row.taskExecutionId = value.executionId().value();
        row.agentRunId = value.agentRunId().value();
        row.runtimeSessionId = value.runtimeSessionId().value();
        row.agentProfileId = value.agentProfileId().value();
        row.agentProfileVersion = value.agentProfileVersion();
        row.agentPrincipalId = value.agentPrincipalId().value();
        row.agentName = value.agentName();
        row.agentScopeUserId = value.agentScopeKey().userId();
        row.agentScopeSessionId = value.agentScopeKey().sessionId();
        row.snapshotSequence = value.snapshotSequence();
        row.checkpointSequence = value.checkpointSequence();
        row.runtimeArtifactId = value.runtimeArtifactId().value();
        row.contentHash = value.contentHash().value();
        row.sizeBytes = value.size();
        copyState(row, value);
        putAudit(row, value.audit(), value.version());
        return row;
    }

    void copyState(AgentStateSnapshotEntity row, AgentStateSnapshot value) {
        row.status = value.status().name();
        row.invalidReasonCode = value.invalidReasonCode().orElse(null);
        row.updatedAt = value.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(value.audit().updatedBy(), "snapshot.updatedBy");
    }

    AgentStateSnapshot toDomain(AgentStateSnapshotEntity row) {
        return AgentStateSnapshot.reconstitute(
                new AgentStateSnapshotId(row.id), scope(row),
                new TaskExecutionId(row.taskExecutionId), new AgentRunId(row.agentRunId),
                new AgentRuntimeSessionId(row.runtimeSessionId),
                new AgentProfileId(row.agentProfileId), row.agentProfileVersion,
                new PrincipalId(row.agentPrincipalId), row.agentName,
                new AgentScopeSessionKey(row.agentScopeUserId, row.agentScopeSessionId),
                row.snapshotSequence, row.checkpointSequence,
                new RuntimeArtifactId(row.runtimeArtifactId),
                new RuntimeContentHash(row.contentHash.trim()), row.sizeBytes,
                AgentStateSnapshotStatus.valueOf(row.status),
                Optional.ofNullable(row.invalidReasonCode), row.version, audit(row));
    }

    TaskProviderAuthorization providerAuthorization(
            UUID bindingId, long bindingVersion, UUID connectionGrantId,
            Long connectionGrantVersion, List<String> capabilities, List<String> resources) {
        return new TaskProviderAuthorization(
                new ProviderBindingId(bindingId), bindingVersion,
                optional(connectionGrantId, ConnectionGrantId::new),
                Optional.ofNullable(connectionGrantVersion), ProviderCapabilities.from(capabilities),
                new ProviderResourceScope(false, Set.copyOf(resources)));
    }

    private static void putExecutionPrincipal(PolicySnapshotEntity row, ExecutionPrincipalSnapshot value) {
        row.executionPrincipalId = value.principalId().value();
        row.executionAssignmentId = value.assignmentId().value();
        row.executionAssignmentVersion = value.assignmentVersion();
        row.responsibilitySnapshotHash = value.responsibilitySnapshotHash().value();
    }

    private static void putExecutionPrincipal(PlanVersionEntity row, ExecutionPrincipalSnapshot value) {
        row.executionPrincipalId = value.principalId().value();
        row.executionAssignmentId = value.assignmentId().value();
        row.executionAssignmentVersion = value.assignmentVersion();
        row.responsibilitySnapshotHash = value.responsibilitySnapshotHash().value();
    }

    private static void putExecutionPrincipal(StepExecutionEntity row, ExecutionPrincipalSnapshot value) {
        row.executionPrincipalId = value.principalId().value();
        row.executionAssignmentId = value.assignmentId().value();
        row.executionAssignmentVersion = value.assignmentVersion();
        row.responsibilitySnapshotHash = value.responsibilitySnapshotHash().value();
    }

    private static void putExecutionPrincipal(TaskCredentialGrantEntity row, ExecutionPrincipalSnapshot value) {
        row.executionPrincipalId = value.principalId().value();
        row.executionAssignmentId = value.assignmentId().value();
        row.executionAssignmentVersion = value.assignmentVersion();
        row.responsibilitySnapshotHash = value.responsibilitySnapshotHash().value();
    }

    private static ExecutionPrincipalSnapshot executionPrincipal(PolicySnapshotEntity row) {
        return executionPrincipal(row.executionPrincipalId, row.executionAssignmentId,
                row.executionAssignmentVersion, row.responsibilitySnapshotHash);
    }

    private static ExecutionPrincipalSnapshot executionPrincipal(PlanVersionEntity row) {
        return executionPrincipal(row.executionPrincipalId, row.executionAssignmentId,
                row.executionAssignmentVersion, row.responsibilitySnapshotHash);
    }

    private static ExecutionPrincipalSnapshot executionPrincipal(StepExecutionEntity row) {
        return executionPrincipal(row.executionPrincipalId, row.executionAssignmentId,
                row.executionAssignmentVersion, row.responsibilitySnapshotHash);
    }

    private static ExecutionPrincipalSnapshot executionPrincipal(TaskCredentialGrantEntity row) {
        return executionPrincipal(row.executionPrincipalId, row.executionAssignmentId,
                row.executionAssignmentVersion, row.responsibilitySnapshotHash);
    }

    private static ExecutionPrincipalSnapshot executionPrincipal(
            UUID principalId, UUID assignmentId, long assignmentVersion, String hash) {
        return new ExecutionPrincipalSnapshot(new PrincipalId(principalId),
                new ResponsibilityAssignmentId(assignmentId), assignmentVersion,
                new TaskFactHash(hash.trim()));
    }

    private static void putSafetyOverlay(PlanVersionEntity row, SafetyEnforcementOverlayReference value) {
        row.safetyOverlayId = value.id().value();
        row.safetyOverlayVersion = value.version();
        row.safetyOverlayHash = value.overlayHash().value();
    }

    private static void putSafetyOverlay(StepExecutionEntity row, SafetyEnforcementOverlayReference value) {
        row.safetyOverlayId = value.id().value();
        row.safetyOverlayVersion = value.version();
        row.safetyOverlayHash = value.overlayHash().value();
    }

    private static void putSafetyOverlay(TaskCredentialGrantEntity row, SafetyEnforcementOverlayReference value) {
        row.safetyOverlayId = value.id().value();
        row.safetyOverlayVersion = value.version();
        row.safetyOverlayHash = value.overlayHash().value();
    }

    private static SafetyEnforcementOverlayReference safetyOverlay(PlanVersionEntity row) {
        return safetyOverlay(row.safetyOverlayId, row.safetyOverlayVersion, row.safetyOverlayHash);
    }

    private static SafetyEnforcementOverlayReference safetyOverlay(StepExecutionEntity row) {
        return safetyOverlay(row.safetyOverlayId, row.safetyOverlayVersion, row.safetyOverlayHash);
    }

    private static SafetyEnforcementOverlayReference safetyOverlay(TaskCredentialGrantEntity row) {
        return safetyOverlay(row.safetyOverlayId, row.safetyOverlayVersion, row.safetyOverlayHash);
    }

    private static SafetyEnforcementOverlayReference safetyOverlay(
            UUID id, long version, String hash) {
        return new SafetyEnforcementOverlayReference(new SafetyEnforcementOverlayId(id), version,
                new TaskFactHash(hash.trim()));
    }

    private static WorkItemScope scope(WorkScopedRow row) {
        return new WorkItemScope(new OrganizationId(row.organizationId), new TeamId(row.teamId),
                new WorkspaceId(row.workspaceId), new WorkProjectId(row.projectId));
    }

    private static void putScope(WorkScopedRow row, WorkItemScope scope) {
        row.organizationId = scope.organizationId().value();
        row.teamId = scope.teamId().value();
        row.workspaceId = scope.workspaceId().value();
        row.projectId = scope.projectId().value();
    }

    private static void putImmutableAudit(
            AuditedVersionedRow row, PrincipalId actor, UtcTimestamp occurredAt) {
        row.version = 0;
        row.createdAt = occurredAt.value();
        row.createdByPrincipalId = actor.value();
        row.updatedAt = occurredAt.value();
        row.updatedByPrincipalId = actor.value();
    }

    private static void putAudit(AuditedVersionedRow row, AuditMetadata audit, long version) {
        row.version = version;
        row.createdAt = audit.createdAt().value();
        row.createdByPrincipalId = principal(audit.createdBy(), "createdBy");
        row.updatedAt = audit.updatedAt().value();
        row.updatedByPrincipalId = principal(audit.updatedBy(), "updatedBy");
    }

    private static AuditMetadata audit(AuditedVersionedRow row) {
        return new AuditMetadata(Optional.of(new PrincipalId(row.createdByPrincipalId)),
                new UtcTimestamp(row.createdAt),
                Optional.of(new PrincipalId(row.updatedByPrincipalId)),
                new UtcTimestamp(row.updatedAt));
    }

    private static UUID principal(Optional<PrincipalId> value, String field) {
        return Objects.requireNonNull(value, field)
                .orElseThrow(() -> new IllegalArgumentException(field + " is required")).value();
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static List<String> enumNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().toList();
    }

    private static <T> Set<T> enumSet(
            List<String> values, java.util.function.Function<String, T> factory) {
        return values.stream().map(factory).collect(Collectors.toUnmodifiableSet());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static <S, T> Optional<T> optional(
            S value, java.util.function.Function<S, T> factory) {
        return value == null ? Optional.empty() : Optional.of(factory.apply(value));
    }
}
