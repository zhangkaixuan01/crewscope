package io.crewscope.infrastructure.persistence.taskruntime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Scalar JPA rows for M3 Task Runtime facts.
 *
 * <p>Relationships stay as explicit UUID coordinates. The database owns scope closure through V10
 * composite foreign keys, while adapters always add an Organization predicate to reads.
 */
final class TaskRuntimeJpaEntities {
    private TaskRuntimeJpaEntities() {}
}

@MappedSuperclass
abstract class AuditedVersionedRow {
    @Version @Column(name = "version", nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
}

@MappedSuperclass
abstract class WorkScopedRow extends AuditedVersionedRow {
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "project_id", nullable = false) UUID projectId;
}

@Entity
@Table(name = "task_responsibility_snapshot", schema = "crewscope")
class TaskResponsibilitySnapshotEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "work_item_id", nullable = false) UUID workItemId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String snapshotHash;
    @Column(name = "captured_at", nullable = false) Instant capturedAt;

    protected TaskResponsibilitySnapshotEntity() {}
}

@Entity
@Table(name = "task", schema = "crewscope")
class TaskEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "work_item_id", nullable = false) UUID workItemId;
    @Column(name = "source_type", nullable = false, length = 32) String sourceType;
    @Column(name = "source_work_item_version", nullable = false) long sourceWorkItemVersion;
    @Column(name = "source_conversation_id") UUID sourceConversationId;
    @Column(name = "source_input_type", length = 32) String sourceInputType;
    @Column(name = "source_input_id") UUID sourceInputId;
    @Column(name = "source_input_version") Long sourceInputVersion;
    @Column(name = "responsibility_snapshot_id", nullable = false) UUID responsibilitySnapshotId;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "current_execution_id") UUID currentExecutionId;
    @Column(name = "cancelled_by_principal_id") UUID cancelledByPrincipalId;
    @Column(name = "cancelled_at") Instant cancelledAt;
    @Column(name = "cancellation_reason", length = 2000) String cancellationReason;

    protected TaskEntity() {}
}

@Entity
@Table(name = "conversation_task_link", schema = "crewscope")
class ConversationTaskLinkEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "work_item_id", nullable = false) UUID workItemId;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(nullable = false, length = 32) String origin;

    protected ConversationTaskLinkEntity() {}
}

@Entity
@Table(name = "task_execution", schema = "crewscope")
class TaskExecutionEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(nullable = false) int attempt;
    @Column(name = "max_attempts", nullable = false) int maxAttempts;
    @Column(name = "parent_execution_id") UUID parentExecutionId;
    @Column(nullable = false) int priority;
    @Column(name = "not_before", nullable = false) Instant notBefore;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "waiting_reason", length = 32) String waitingReason;
    @Column(name = "waiting_since") Instant waitingSince;
    @Column(name = "control_request_type", length = 32) String controlRequestType;
    @Column(name = "control_requested_by_principal_id") UUID controlRequestedByPrincipalId;
    @Column(name = "control_requested_at") Instant controlRequestedAt;
    @Column(name = "control_request_reason", length = 2000) String controlRequestReason;
    @Column(name = "terminal_decided_by_principal_id") UUID terminalDecidedByPrincipalId;
    @Column(name = "terminal_decided_at") Instant terminalDecidedAt;
    @Column(name = "terminal_failure_class", length = 32) String terminalFailureClass;
    @Column(name = "terminal_failure_code", length = 100) String terminalFailureCode;
    @Column(name = "execution_principal_id") UUID executionPrincipalId;
    @Column(name = "execution_assignment_id") UUID executionAssignmentId;
    @Column(name = "execution_assignment_version") Long executionAssignmentVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "responsibility_snapshot_hash", length = 64, columnDefinition = "char(64)") String responsibilitySnapshotHash;
    @Column(name = "current_policy_snapshot_id") UUID currentPolicySnapshotId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "current_policy_snapshot_hash", length = 64, columnDefinition = "char(64)") String currentPolicySnapshotHash;
    @Column(name = "current_safety_overlay_id") UUID currentSafetyOverlayId;
    @Column(name = "current_safety_overlay_version") Long currentSafetyOverlayVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "current_safety_overlay_hash", length = 64, columnDefinition = "char(64)") String currentSafetyOverlayHash;
    @Column(name = "current_plan_version_id") UUID currentPlanVersionId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "current_plan_version_hash", length = 64, columnDefinition = "char(64)") String currentPlanVersionHash;
    @Column(name = "last_fencing_token") Long lastFencingToken;

    protected TaskExecutionEntity() {}
}

@Entity
@Table(name = "policy_snapshot", schema = "crewscope")
class PolicySnapshotEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(nullable = false) long revision;
    @Column(name = "parent_snapshot_id") UUID parentSnapshotId;
    @Column(name = "change_reason", nullable = false, length = 64) String changeReason;
    @Column(name = "execution_principal_id", nullable = false) UUID executionPrincipalId;
    @Column(name = "execution_assignment_id", nullable = false) UUID executionAssignmentId;
    @Column(name = "execution_assignment_version", nullable = false) long executionAssignmentVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "responsibility_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String responsibilitySnapshotHash;
    @Column(name = "policy_pack_id", nullable = false) UUID policyPackId;
    @Column(name = "policy_pack_version", nullable = false) long policyPackVersion;
    @Column(name = "agent_profile_id", nullable = false) UUID agentProfileId;
    @Column(name = "agent_profile_version", nullable = false) long agentProfileVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<String> capabilities;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "allowed_tools", nullable = false, columnDefinition = "jsonb") List<String> allowedTools;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "provider_binding_ids", nullable = false, columnDefinition = "jsonb") List<String> providerBindingIds;
    @Column(name = "max_tokens", nullable = false) long maxTokens;
    @Column(name = "max_model_calls", nullable = false) int maxModelCalls;
    @Column(name = "max_tool_calls", nullable = false) int maxToolCalls;
    @Column(name = "max_duration_seconds", nullable = false) long maxDurationSeconds;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String snapshotHash;

    protected PolicySnapshotEntity() {}
}

record SafetyOverlayKey(UUID id, long overlayVersion) implements Serializable {}

@Entity
@IdClass(SafetyOverlayKey.class)
@Table(name = "safety_enforcement_overlay", schema = "crewscope")
class SafetyEnforcementOverlayEntity extends WorkScopedRow {
    @Id UUID id;
    @Id @Column(name = "overlay_version") long overlayVersion;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "parent_overlay_hash", length = 64, columnDefinition = "char(64)") String parentOverlayHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<String> restrictions;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "disabled_capabilities", nullable = false, columnDefinition = "jsonb") List<String> disabledCapabilities;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "disabled_tools", nullable = false, columnDefinition = "jsonb") List<String> disabledTools;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "overlay_hash", nullable = false, length = 64, columnDefinition = "char(64)") String overlayHash;

    protected SafetyEnforcementOverlayEntity() {}
}

@Entity
@Table(name = "plan_version", schema = "crewscope")
class PlanVersionEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(nullable = false) long revision;
    @Column(name = "parent_version_id") UUID parentVersionId;
    @Column(name = "change_reason", nullable = false, length = 64) String changeReason;
    @Column(name = "policy_snapshot_id", nullable = false) UUID policySnapshotId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "policy_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String policySnapshotHash;
    @Column(name = "safety_overlay_id", nullable = false) UUID safetyOverlayId;
    @Column(name = "safety_overlay_version", nullable = false) long safetyOverlayVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "safety_overlay_hash", nullable = false, length = 64, columnDefinition = "char(64)") String safetyOverlayHash;
    @Column(name = "execution_principal_id", nullable = false) UUID executionPrincipalId;
    @Column(name = "execution_assignment_id", nullable = false) UUID executionAssignmentId;
    @Column(name = "execution_assignment_version", nullable = false) long executionAssignmentVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "responsibility_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String responsibilitySnapshotHash;
    @Column(nullable = false, columnDefinition = "text") String markdown;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "char(64)") String contentHash;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "version_hash", nullable = false, length = 64, columnDefinition = "char(64)") String versionHash;
    @Column(name = "published_by_principal_id", nullable = false) UUID publishedByPrincipalId;
    @Column(name = "published_at", nullable = false) Instant publishedAt;

    protected PlanVersionEntity() {}
}

@Entity
@Table(name = "step_execution", schema = "crewscope")
class StepExecutionEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(name = "plan_version_id", nullable = false) UUID planVersionId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "plan_version_hash", nullable = false, length = 64, columnDefinition = "char(64)") String planVersionHash;
    @Column(name = "plan_step_key", nullable = false, length = 64) String planStepKey;
    @Column(nullable = false) int sequence;
    @Column(nullable = false) boolean critical;
    @Column(name = "execution_principal_id", nullable = false) UUID executionPrincipalId;
    @Column(name = "execution_assignment_id", nullable = false) UUID executionAssignmentId;
    @Column(name = "execution_assignment_version", nullable = false) long executionAssignmentVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "responsibility_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String responsibilitySnapshotHash;
    @Column(name = "policy_snapshot_id", nullable = false) UUID policySnapshotId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "policy_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String policySnapshotHash;
    @Column(name = "safety_overlay_id", nullable = false) UUID safetyOverlayId;
    @Column(name = "safety_overlay_version", nullable = false) long safetyOverlayVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "safety_overlay_hash", nullable = false, length = 64, columnDefinition = "char(64)") String safetyOverlayHash;
    @Column(name = "run_attempt", nullable = false) int runAttempt;
    @Column(name = "max_run_attempts", nullable = false) int maxRunAttempts;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "wait_reason", length = 32) String waitReason;
    @Column(name = "checkpoint_sequence") Long checkpointSequence;
    @Column(name = "checkpoint_code", length = 64) String checkpointCode;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "checkpoint_payload_hash", length = 64, columnDefinition = "char(64)") String checkpointPayloadHash;
    @Column(name = "checkpoint_recorded_by_principal_id") UUID checkpointRecordedByPrincipalId;
    @Column(name = "checkpoint_recorded_at") Instant checkpointRecordedAt;
    @Column(name = "failure_class", length = 32) String failureClass;
    @Column(name = "failure_code", length = 100) String failureCode;

    protected StepExecutionEntity() {}
}

@Entity
@Table(name = "execution_runtime", schema = "crewscope")
class ExecutionRuntimeEntity extends AuditedVersionedRow {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "runtime_environment", nullable = false, length = 64) String runtimeEnvironment;
    @Column(name = "runtime_key", nullable = false, length = 64) String runtimeKey;
    @Column(name = "display_name", nullable = false, length = 200) String displayName;
    @Column(name = "implementation_version", nullable = false, length = 64) String implementationVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<String> capabilities;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<String> languages;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "build_systems", nullable = false, columnDefinition = "jsonb") List<String> buildSystems;
    @Column(nullable = false, length = 32) String status;

    protected ExecutionRuntimeEntity() {}
}

@Entity
@Table(name = "runtime_worker", schema = "crewscope")
class RuntimeWorkerEntity extends AuditedVersionedRow {
    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "runtime_environment", nullable = false, length = 64) String runtimeEnvironment;
    @Column(name = "runtime_id", nullable = false) UUID runtimeId;
    @Column(name = "stable_key", nullable = false, length = 128) String stableKey;
    @Column(name = "runtime_profile", nullable = false, length = 16) String runtimeProfile;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<String> capabilities;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<String> languages;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "build_systems", nullable = false, columnDefinition = "jsonb") List<String> buildSystems;
    @Column(name = "max_concurrent_executions", nullable = false) int maxConcurrentExecutions;
    @Column(name = "active_executions", nullable = false) int activeExecutions;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "last_heartbeat_at", nullable = false) Instant lastHeartbeatAt;
    @Column(name = "heartbeat_sequence", nullable = false) long heartbeatSequence;

    protected RuntimeWorkerEntity() {}
}

@Entity
@Table(name = "task_credential_grant", schema = "crewscope")
class TaskCredentialGrantEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(nullable = false) int attempt;
    @Column(name = "execution_lease_id", nullable = false) UUID executionLeaseId;
    @Column(name = "runtime_environment", nullable = false, length = 64) String runtimeEnvironment;
    @Column(name = "runtime_id", nullable = false) UUID runtimeId;
    @Column(name = "worker_id", nullable = false) UUID workerId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "claim_token_hash", nullable = false, length = 64, columnDefinition = "char(64)") String claimTokenHash;
    @Column(name = "fencing_token", nullable = false) long fencingToken;
    @Column(name = "execution_principal_id", nullable = false) UUID executionPrincipalId;
    @Column(name = "execution_assignment_id", nullable = false) UUID executionAssignmentId;
    @Column(name = "execution_assignment_version", nullable = false) long executionAssignmentVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "responsibility_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String responsibilitySnapshotHash;
    @Column(name = "policy_snapshot_id", nullable = false) UUID policySnapshotId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "policy_snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)") String policySnapshotHash;
    @Column(name = "safety_overlay_id", nullable = false) UUID safetyOverlayId;
    @Column(name = "safety_overlay_version", nullable = false) long safetyOverlayVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "safety_overlay_hash", nullable = false, length = 64, columnDefinition = "char(64)") String safetyOverlayHash;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "jti_hash", nullable = false, length = 64, columnDefinition = "char(64)") String jtiHash;
    @Column(name = "issued_at", nullable = false) Instant issuedAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(nullable = false, length = 16) String status;
    @Column(name = "use_count", nullable = false) long useCount;
    @Column(name = "last_used_at") Instant lastUsedAt;
    @Column(name = "terminated_by_principal_id") UUID terminatedByPrincipalId;
    @Column(name = "terminated_at") Instant terminatedAt;
    @Column(name = "termination_reason", length = 2000) String terminationReason;

    protected TaskCredentialGrantEntity() {}
}

/** Task-side view of the V7/V10 shared AgentRuntimeSession table. */
@Entity
@Table(name = "agent_runtime_session", schema = "crewscope")
class TaskAgentRuntimeSessionEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id") UUID taskId;
    @Column(name = "task_execution_id") UUID taskExecutionId;
    @Column(name = "step_execution_id") UUID stepExecutionId;
    @Column(name = "session_purpose", nullable = false, length = 32) String sessionPurpose;
    @Column(name = "agent_principal_id", nullable = false) UUID agentPrincipalId;
    @Column(name = "agent_profile_id", nullable = false) UUID agentProfileId;
    @Column(name = "agent_profile_version", nullable = false) long agentProfileVersion;
    @Column(name = "agent_scope_user_id", nullable = false, length = 500) String agentScopeUserId;
    @Column(name = "agent_scope_session_id", nullable = false, length = 500) String agentScopeSessionId;
    @Column(name = "state_reference", nullable = false, length = 500) String stateReference;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "agent_principal_type", nullable = false, length = 32) String agentPrincipalType;
    @Column(name = "agent_profile_type", nullable = false, length = 32) String agentProfileType;

    protected TaskAgentRuntimeSessionEntity() {}
}

@Entity
@Table(name = "agent_run", schema = "crewscope")
class AgentRunEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(name = "step_execution_id") UUID stepExecutionId;
    @Column(name = "runtime_session_id", nullable = false) UUID runtimeSessionId;
    @Column(name = "agent_principal_id", nullable = false) UUID agentPrincipalId;
    @Column(name = "agent_profile_id", nullable = false) UUID agentProfileId;
    @Column(name = "agent_profile_version", nullable = false) long agentProfileVersion;
    @Column(name = "run_sequence", nullable = false) long runSequence;
    @Column(nullable = false, length = 16) String status;
    @Column(name = "continuity_previous_run_id") UUID continuityPreviousRunId;
    @Column(name = "continuity_snapshot_id") UUID continuitySnapshotId;
    @Column(name = "first_missing_checkpoint") Long firstMissingCheckpoint;
    @Column(name = "last_missing_checkpoint") Long lastMissingCheckpoint;
    @Column(name = "continuity_reason", length = 64) String continuityReason;
    @Column(name = "continuity_detected_at") Instant continuityDetectedAt;
    @Column(name = "terminal_failure_code", length = 100) String terminalFailureCode;
    @Column(name = "terminal_result_artifact_id") UUID terminalResultArtifactId;
    @Column(name = "terminal_at") Instant terminalAt;

    protected AgentRunEntity() {}
}

@Entity
@Table(name = "agent_interrupt", schema = "crewscope")
class AgentInterruptEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(name = "agent_run_id", nullable = false) UUID agentRunId;
    @Column(name = "segment_sequence", nullable = false) long segmentSequence;
    @Column(nullable = false, length = 32) String kind;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "interrupt_token_hash", nullable = false, length = 64, columnDefinition = "char(64)") String interruptTokenHash;
    @Column(nullable = false, length = 16) String status;
    @Column(name = "resume_request_id") UUID resumeRequestId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "response_hash", length = 64, columnDefinition = "char(64)") String responseHash;
    @Column(name = "resolved_by_principal_id") UUID resolvedByPrincipalId;
    @Column(name = "resolved_at") Instant resolvedAt;

    protected AgentInterruptEntity() {}
}

@Entity
@Table(name = "runtime_artifact", schema = "crewscope")
class RuntimeArtifactEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "artifact_id", nullable = false) UUID artifactId;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(name = "step_execution_id") UUID stepExecutionId;
    @Column(name = "agent_run_id", nullable = false) UUID agentRunId;
    @Column(nullable = false, length = 32) String kind;
    @Column(name = "content_type", nullable = false, length = 255) String contentType;
    @Column(name = "size_bytes", nullable = false) long sizeBytes;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "char(64)") String contentHash;
    @Column(name = "retention_until") Instant retentionUntil;

    protected RuntimeArtifactEntity() {}
}

@Entity
@Table(name = "agent_state_snapshot", schema = "crewscope")
class AgentStateSnapshotEntity extends WorkScopedRow {
    @Id UUID id;
    @Column(name = "task_id", nullable = false) UUID taskId;
    @Column(name = "task_execution_id", nullable = false) UUID taskExecutionId;
    @Column(name = "agent_run_id", nullable = false) UUID agentRunId;
    @Column(name = "runtime_session_id", nullable = false) UUID runtimeSessionId;
    @Column(name = "agent_profile_id", nullable = false) UUID agentProfileId;
    @Column(name = "agent_profile_version", nullable = false) long agentProfileVersion;
    @Column(name = "agent_principal_id", nullable = false) UUID agentPrincipalId;
    @Column(name = "agent_name", nullable = false, length = 100) String agentName;
    @Column(name = "agent_scope_user_id", nullable = false, length = 500) String agentScopeUserId;
    @Column(name = "agent_scope_session_id", nullable = false, length = 500) String agentScopeSessionId;
    @Column(name = "snapshot_sequence", nullable = false) long snapshotSequence;
    @Column(name = "checkpoint_sequence", nullable = false) long checkpointSequence;
    @Column(name = "runtime_artifact_id", nullable = false) UUID runtimeArtifactId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "content_hash", nullable = false, length = 64, columnDefinition = "char(64)") String contentHash;
    @Column(name = "size_bytes", nullable = false) long sizeBytes;
    @Column(nullable = false, length = 16) String status;
    @Column(name = "invalid_reason_code", length = 100) String invalidReasonCode;

    protected AgentStateSnapshotEntity() {}
}
