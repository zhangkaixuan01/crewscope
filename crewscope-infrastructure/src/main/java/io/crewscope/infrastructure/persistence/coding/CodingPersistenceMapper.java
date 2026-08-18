package io.crewscope.infrastructure.persistence.coding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.CodingCheckpointTodo;
import io.crewscope.domain.coding.CodingCheckpointWorkState;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotChangeReason;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffFileKind;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceArtifactKind;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceFailureClassification;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceCompletionReason;
import io.crewscope.domain.coding.ExecutionWorkspaceFailure;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.ExecutionWorkspaceOwnership;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.ManagedWorkspaceBranch;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.coding.TestStatistics;
import io.crewscope.domain.coding.WorkspaceArchiveReference;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
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
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentStateSnapshotId;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reconstitutes validated Coding aggregates from scalar V14 rows and canonical JSON values. */
@Component
public final class CodingPersistenceMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<CommandKind, BuildCommand>> COMMAND_CATALOG =
            new TypeReference<>() {};
    private static final TypeReference<List<CodingCheckpointTodo>> TODOS = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public CodingPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Coding persistence JSON serialization failed", failure);
        }
    }

    public List<String> stringList(String json) {
        return read(json, STRING_LIST);
    }

    public CommandCatalog commandCatalog(String json) {
        return new CommandCatalog(read(json, COMMAND_CATALOG));
    }

    public List<CodingCheckpointTodo> todos(String json) {
        return read(json, TODOS);
    }

    public RepositoryBinding repositoryBinding(ResultSet row) throws SQLException {
        return RepositoryBinding.reconstitute(
                new RepositoryBindingId(uuid(row, "id")),
                new RepositoryBindingScope(
                        organization(row), team(row), workspace(row), project(row)),
                RepositoryKind.valueOf(row.getString("repository_kind")),
                new RepositoryKey(row.getString("repository_key")),
                new RepositoryBranchName(row.getString("default_branch")),
                RepositoryBindingStatus.valueOf(row.getString("status")),
                row.getLong("version"),
                audit(row));
    }

    public CodingTargetSnapshot codingTarget(ResultSet row) throws SQLException {
        return CodingTargetSnapshot.reconstitute(
                new CodingTargetSnapshotId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                taskHash(row, "task_brief_hash"),
                row.getLong("revision"),
                optionalUuid(row, "parent_snapshot_id").map(CodingTargetSnapshotId::new),
                CodingTargetSnapshotChangeReason.valueOf(row.getString("change_reason")),
                new RepositoryBindingId(uuid(row, "repository_binding_id")),
                row.getLong("repository_binding_version"),
                RepositoryKind.valueOf(row.getString("repository_kind")),
                new RepositoryKey(row.getString("repository_key")),
                new RepositoryBranchName(row.getString("baseline_ref")),
                new RepositoryCommitId(row.getString("baseline_commit")),
                new CodingTargetAllowedPaths(stringList(row.getString("allowed_paths"))),
                buildProfile(row),
                stringList(row.getString("acceptance_criteria")),
                taskHash(row, "snapshot_hash"),
                new PrincipalId(uuid(row, "created_by_principal_id")),
                time(row, "created_at"));
    }

    public ExecutionWorkspace executionWorkspace(ResultSet row) throws SQLException {
        return ExecutionWorkspace.reconstitute(
                new ExecutionWorkspaceId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                codingTargetReference(row),
                new RepositoryBindingId(uuid(row, "repository_binding_id")),
                row.getLong("repository_binding_version"),
                new RepositoryKey(row.getString("repository_key")),
                new RepositoryCommitId(row.getString("baseline_commit")),
                new ExecutionWorkspaceKey(row.getString("workspace_key")),
                new ManagedWorkspaceBranch(row.getString("managed_branch")),
                new WorkspaceArchiveReference(row.getString("archive_reference")),
                new ExecutionWorkspaceOwnership(
                        new RuntimeEnvironment(row.getString("runtime_environment")),
                        new ExecutionRuntimeId(uuid(row, "runtime_id")),
                        new RuntimeWorkerId(uuid(row, "worker_id")),
                        new ExecutionLeaseId(uuid(row, "execution_lease_id")),
                        new FencingToken(row.getLong("fencing_token"))),
                ExecutionWorkspaceStatus.valueOf(row.getString("status")),
                optionalString(row, "recovery_target_status").map(ExecutionWorkspaceStatus::valueOf),
                row.getLong("recovery_generation"),
                optionalString(row, "completion_reason").map(ExecutionWorkspaceCompletionReason::valueOf),
                optionalString(row, "failure_code").map(ExecutionWorkspaceFailure::new),
                new ExecutionWorkspaceRetention(time(row, "retain_until")),
                new ExecutionWorkspaceFingerprint(row.getString("workspace_fingerprint")),
                row.getLong("version"),
                audit(row));
    }

    public WorkspacePolicy workspacePolicy(ResultSet row) throws SQLException {
        return WorkspacePolicy.reconstitute(
                new WorkspacePolicyId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                codingTargetReference(row),
                new PolicySnapshotId(uuid(row, "policy_snapshot_id")),
                taskHash(row, "policy_snapshot_hash"),
                new AllowedPathSet(stringList(row.getString("allowed_paths"))),
                buildProfile(row),
                commandCatalog(row.getString("command_catalog")),
                sandboxBudget(row),
                operationBudget(row),
                taskHash(row, "policy_hash"),
                new PrincipalId(uuid(row, "created_by_principal_id")),
                time(row, "created_at"));
    }

    public WorkspacePolicyOverlay workspacePolicyOverlay(ResultSet row, WorkspacePolicy policy)
            throws SQLException {
        return WorkspacePolicyOverlay.reconstitute(
                new WorkspacePolicyOverlayId(uuid(row, "id")),
                policy,
                row.getLong("overlay_version"),
                optionalString(row, "parent_overlay_hash").map(TaskFactHash::new),
                new AllowedPathSet(stringList(row.getString("allowed_paths"))),
                commandCatalog(row.getString("command_catalog")),
                sandboxBudget(row),
                operationBudget(row),
                taskHash(row, "overlay_hash"),
                audit(row));
    }

    public DiffArtifact diffArtifact(ResultSet row, List<DiffFileEntry> files) throws SQLException {
        DiffManifest manifest = DiffManifest.reconstitute(
                new DiffGeneration(row.getLong("diff_generation")),
                files,
                row.getInt("file_count"),
                row.getLong("additions"),
                row.getLong("deletions"),
                runtimeHash(row, "manifest_hash"));
        return DiffArtifact.reconstitute(
                new DiffArtifactId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                new ExecutionWorkspaceId(uuid(row, "execution_workspace_id")),
                codingTargetReference(row),
                new RepositoryCommitId(row.getString("baseline_commit")),
                new RepositoryCommitId(row.getString("delivery_commit")),
                manifest,
                new PatchArtifactReference(
                        new ArtifactId(uuid(row, "patch_artifact_id")),
                        row.getLong("patch_size_bytes"),
                        runtimeHash(row, "patch_sha256")),
                taskHash(row, "final_hash"),
                createdAudit(row));
    }

    public DiffFileEntry diffFile(ResultSet row) throws SQLException {
        return new DiffFileEntry(
                new DiffPath(row.getString("path")),
                optionalString(row, "old_path").map(DiffPath::new),
                DiffFileKind.valueOf(row.getString("change_kind")),
                row.getLong("additions"),
                row.getLong("deletions"),
                row.getBoolean("is_binary"),
                row.getBoolean("patch_truncated"),
                runtimeHash(row, "patch_sha256"),
                optionalString(row, "patch_preview"));
    }

    public CommandEvidence commandEvidence(ResultSet row) throws SQLException {
        WorkspacePolicyReference policy = workspacePolicyReference(row);
        CommandSpec spec = CommandSpec.reconstitute(
                policy,
                buildProfile(row),
                CommandKind.valueOf(row.getString("command_kind")),
                row.getString("tool_key"),
                stringList(row.getString("argv")),
                row.getString("working_directory"),
                row.getInt("timeout_seconds"),
                new SandboxImageReference(row.getString("sandbox_image")),
                taskHash(row, "command_spec_hash"));
        return CommandEvidence.reconstitute(
                new CommandEvidenceId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                new ExecutionWorkspaceId(uuid(row, "execution_workspace_id")),
                new ExecutionWorkspaceFingerprint(row.getString("workspace_fingerprint")),
                codingTargetReference(row),
                new EvidenceSequence(row.getLong("evidence_sequence")),
                policy,
                spec,
                time(row, "started_at"),
                time(row, "finished_at"),
                CommandTermination.valueOf(row.getString("termination")),
                optionalInteger(row, "exit_code"),
                new EvidenceSummary(row.getString("summary")),
                evidenceArtifact(row, "command_log"),
                optionalString(row, "failure_classification")
                        .map(EvidenceFailureClassification::valueOf),
                taskHash(row, "evidence_hash"),
                createdAudit(row));
    }

    public CommandEvidenceReference commandReference(ResultSet row) throws SQLException {
        return new CommandEvidenceReference(
                new CommandEvidenceId(uuid(row, "command_evidence_id")),
                new EvidenceSequence(row.getLong("command_evidence_sequence")),
                taskHash(row, "command_evidence_hash"),
                optionalString(row, "command_failure_classification")
                        .map(EvidenceFailureClassification::valueOf));
    }

    public TestEvidence testEvidence(
            ResultSet row,
            List<CommandEvidenceReference> commands,
            List<AcceptanceResult> acceptanceResults)
            throws SQLException {
        return TestEvidence.reconstitute(
                new TestEvidenceId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                new ExecutionWorkspaceId(uuid(row, "execution_workspace_id")),
                new ExecutionWorkspaceFingerprint(row.getString("workspace_fingerprint")),
                codingTargetReference(row),
                new DiffGeneration(row.getLong("diff_generation")),
                runtimeHash(row, "diff_manifest_hash"),
                new EvidenceSequence(row.getLong("evidence_sequence")),
                workspacePolicyReference(row),
                commands,
                new TestStatistics(
                        row.getLong("test_total"),
                        row.getLong("test_passed"),
                        row.getLong("test_failed"),
                        row.getLong("test_errors"),
                        row.getLong("test_skipped")),
                acceptanceResults,
                optionalUuid(row, "test_report_artifact_id")
                        .map(ignored -> evidenceArtifactUnchecked(row, "test_report")),
                new EvidenceSummary(row.getString("summary")),
                optionalString(row, "failure_classification")
                        .map(EvidenceFailureClassification::valueOf),
                taskHash(row, "evidence_hash"),
                createdAudit(row));
    }

    public CodingCheckpoint codingCheckpoint(ResultSet row) throws SQLException {
        CodingCheckpointWorkState workState = new CodingCheckpointWorkState(
                row.getString("plan_markdown"),
                todos(row.getString("todos")),
                taskHash(row, "work_state_hash"));
        return CodingCheckpoint.reconstitute(
                new CodingCheckpointId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                codingTargetReference(row),
                new ExecutionWorkspaceId(uuid(row, "execution_workspace_id")),
                new ExecutionWorkspaceFingerprint(row.getString("workspace_fingerprint")),
                workspacePolicyReference(row),
                new AgentRunId(uuid(row, "agent_run_id")),
                row.getLong("agent_run_sequence"),
                row.getLong("segment_sequence"),
                optionalUuid(row, "plan_version_id").map(PlanVersionId::new),
                optionalString(row, "plan_version_hash").map(TaskFactHash::new),
                optionalUuid(row, "step_execution_id").map(StepExecutionId::new),
                workState,
                new DiffGeneration(row.getLong("diff_generation")),
                runtimeHash(row, "diff_manifest_hash"),
                optionalUuid(row, "test_evidence_id").map(TestEvidenceId::new),
                optionalString(row, "test_evidence_hash").map(TaskFactHash::new),
                new AgentStateSnapshotId(uuid(row, "agent_state_snapshot_id")),
                row.getLong("snapshot_sequence"),
                runtimeHash(row, "snapshot_content_hash"),
                row.getLong("checkpoint_sequence"),
                taskHash(row, "checkpoint_hash"),
                createdAudit(row));
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Coding persistence JSON is invalid", failure);
        }
    }

    private static WorkItemScope scope(ResultSet row) throws SQLException {
        return new WorkItemScope(organization(row), team(row), workspace(row), project(row));
    }

    private static OrganizationId organization(ResultSet row) throws SQLException {
        return new OrganizationId(uuid(row, "organization_id"));
    }

    private static TeamId team(ResultSet row) throws SQLException {
        return new TeamId(uuid(row, "team_id"));
    }

    private static WorkspaceId workspace(ResultSet row) throws SQLException {
        return new WorkspaceId(uuid(row, "workspace_id"));
    }

    private static WorkProjectId project(ResultSet row) throws SQLException {
        return new WorkProjectId(uuid(row, "project_id"));
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                optionalUuid(row, "created_by_principal_id").map(PrincipalId::new),
                time(row, "created_at"),
                optionalUuid(row, "updated_by_principal_id").map(PrincipalId::new),
                time(row, "updated_at"));
    }

    private static AuditMetadata createdAudit(ResultSet row) throws SQLException {
        return AuditMetadata.createdBy(
                new PrincipalId(uuid(row, "created_by_principal_id")), time(row, "created_at"));
    }

    private static CodingTargetSnapshotReference codingTargetReference(ResultSet row)
            throws SQLException {
        return new CodingTargetSnapshotReference(
                new CodingTargetSnapshotId(uuid(row, "coding_target_snapshot_id")),
                row.getLong("coding_target_revision"),
                taskHash(row, "coding_target_hash"));
    }

    private static WorkspacePolicyReference workspacePolicyReference(ResultSet row)
            throws SQLException {
        return new WorkspacePolicyReference(
                new WorkspacePolicyId(uuid(row, "workspace_policy_id")),
                taskHash(row, "workspace_policy_hash"));
    }

    private static BuildProfileReference buildProfile(ResultSet row) throws SQLException {
        return new BuildProfileReference(
                row.getString("build_profile_key"),
                row.getLong("build_profile_version"),
                taskHash(row, "build_profile_hash"));
    }

    private static SandboxResourceBudget sandboxBudget(ResultSet row) throws SQLException {
        return new SandboxResourceBudget(
                SandboxNetworkMode.valueOf(row.getString("sandbox_network_mode")),
                row.getInt("sandbox_cpu_count"),
                row.getInt("sandbox_memory_mib"),
                row.getInt("sandbox_pids"),
                row.getInt("sandbox_max_command_duration_seconds"),
                row.getLong("sandbox_max_command_output_bytes"),
                row.getBoolean("sandbox_read_only_root_filesystem"));
    }

    private static WorkspaceOperationBudget operationBudget(ResultSet row) throws SQLException {
        return new WorkspaceOperationBudget(
                row.getInt("max_command_calls"),
                row.getInt("max_changed_files"),
                row.getLong("max_single_file_bytes"),
                row.getInt("max_write_operations"),
                row.getLong("max_written_bytes"),
                row.getLong("max_diff_bytes"),
                row.getInt("max_test_repair_rounds"));
    }

    private static EvidenceArtifactReference evidenceArtifact(ResultSet row, String prefix)
            throws SQLException {
        return new EvidenceArtifactReference(
                new ArtifactId(uuid(row, prefix + "_artifact_id")),
                EvidenceArtifactKind.valueOf(row.getString(prefix + "_kind")),
                row.getString(prefix + "_content_type"),
                row.getLong(prefix + "_size_bytes"),
                runtimeHash(row, prefix + "_content_hash"));
    }

    private static EvidenceArtifactReference evidenceArtifactUnchecked(ResultSet row, String prefix) {
        try {
            return evidenceArtifact(row, prefix);
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read Evidence Artifact", failure);
        }
    }

    private static TaskFactHash taskHash(ResultSet row, String column) throws SQLException {
        return new TaskFactHash(row.getString(column));
    }

    private static RuntimeContentHash runtimeHash(ResultSet row, String column) throws SQLException {
        return new RuntimeContentHash(row.getString(column));
    }

    private static UtcTimestamp time(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, java.time.OffsetDateTime.class).toInstant());
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    private static Optional<String> optionalString(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getString(column));
    }

    private static Optional<Integer> optionalInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }
}
