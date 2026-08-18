package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.WorkspacePolicyOverlayRepository;
import io.crewscope.application.coding.WorkspacePolicyRepository;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC adapter for immutable WorkspacePolicy and append-only tightening overlays. */
@Repository
public class JdbcWorkspacePolicyRepositoryAdapter
        implements WorkspacePolicyRepository, WorkspacePolicyOverlayRepository {

    private static final String SELECT_POLICY = "SELECT * FROM crewscope.workspace_policy";
    private static final String SELECT_OVERLAY = "SELECT * FROM crewscope.workspace_policy_overlay";

    private final NamedParameterJdbcTemplate jdbc;
    private final CodingPersistenceMapper mapper;

    public JdbcWorkspacePolicyRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, CodingPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public WorkspacePolicy create(WorkspacePolicy policy) {
        WorkspacePolicy required = Objects.requireNonNull(policy, "policy");
        jdbc.update(
                """
                INSERT INTO crewscope.workspace_policy (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt,
                    coding_target_snapshot_id, coding_target_revision, coding_target_hash,
                    policy_snapshot_id, policy_snapshot_hash, allowed_paths,
                    build_profile_key, build_profile_version, build_profile_hash, command_catalog,
                    sandbox_network_mode, sandbox_cpu_count, sandbox_memory_mib, sandbox_pids,
                    sandbox_max_command_duration_seconds, sandbox_max_command_output_bytes,
                    sandbox_read_only_root_filesystem, max_command_calls, max_changed_files,
                    max_single_file_bytes, max_write_operations, max_written_bytes,
                    max_diff_bytes, max_test_repair_rounds, policy_hash,
                    created_at, created_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :taskId, :taskExecutionId, :attempt,
                    :codingTargetId, :codingTargetRevision, :codingTargetHash,
                    :policySnapshotId, :policySnapshotHash, CAST(:allowedPaths AS jsonb),
                    :buildProfileKey, :buildProfileVersion, :buildProfileHash,
                    CAST(:commandCatalog AS jsonb), :networkMode, :cpuCount, :memoryMiB, :pids,
                    :maxCommandDurationSeconds, :maxCommandOutputBytes, :readOnlyRootFilesystem,
                    :maxCommandCalls, :maxChangedFiles, :maxSingleFileBytes,
                    :maxWriteOperations, :maxWrittenBytes, :maxDiffBytes,
                    :maxTestRepairRounds, :policyHash, :createdAt, :createdBy
                )
                """,
                policyParameters(required));
        return findById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("WorkspacePolicy", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspacePolicy> findById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            WorkspacePolicyId policyId) {
        return jdbc.query(
                        SELECT_POLICY + """
                         WHERE organization_id = :organizationId
                           AND team_id = :teamId
                           AND project_id = :projectId
                           AND id = :id
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("id", Objects.requireNonNull(policyId).value()),
                        (row, ignored) -> mapper.workspacePolicy(row))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspacePolicy> findByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return jdbc.query(
                        SELECT_POLICY + """
                         WHERE organization_id = :organizationId
                           AND team_id = :teamId
                           AND project_id = :projectId
                           AND task_execution_id = :taskExecutionId
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("taskExecutionId", Objects.requireNonNull(taskExecutionId).value()),
                        (row, ignored) -> mapper.workspacePolicy(row))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public WorkspacePolicyOverlay create(WorkspacePolicyOverlay overlay) {
        WorkspacePolicyOverlay required = Objects.requireNonNull(overlay, "overlay");
        if (required.version() != 1 || required.parentOverlayHash().isPresent()) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay.version", "create requires the first overlay version");
        }
        WorkspacePolicy base = requirePolicy(required.workspacePolicy().id());
        jdbc.update(overlayInsert("VALUES"), overlayParameters(required, base));
        return findByIdAndVersion(
                        base.scope().organizationId(),
                        base.scope().teamId(),
                        base.scope().projectId(),
                        required.id(),
                        required.version())
                .orElseThrow(() -> new AggregateNotFoundException("WorkspacePolicyOverlay", required.id()));
    }

    @Override
    @Transactional
    public WorkspacePolicyOverlay appendSuccessor(
            WorkspacePolicyOverlay overlay, TaskFactHash expectedCurrentOverlayHash) {
        WorkspacePolicyOverlay required = Objects.requireNonNull(overlay, "overlay");
        TaskFactHash expected = Objects.requireNonNull(
                expectedCurrentOverlayHash, "expectedCurrentOverlayHash");
        if (required.version() < 2
                || required.parentOverlayHash().filter(expected::equals).isEmpty()) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay.parentOverlayHash",
                    "must match the expected current Overlay Hash");
        }
        WorkspacePolicy base = requirePolicy(required.workspacePolicy().id());
        MapSqlParameterSource parameters = overlayParameters(required, base)
                .addValue("expectedCurrentHash", expected.value())
                .addValue("expectedCurrentVersion", required.version() - 1);
        int inserted = jdbc.update(
                overlayInsert(
                        """
                        SELECT
                            :id, :overlayVersion, :organizationId, :teamId, :workspaceId,
                            :projectId, :taskId, :taskExecutionId, :attempt,
                            :policyId, :policyHash, :parentOverlayHash,
                            CAST(:allowedPaths AS jsonb), CAST(:commandCatalog AS jsonb),
                            :networkMode, :cpuCount, :memoryMiB, :pids,
                            :maxCommandDurationSeconds, :maxCommandOutputBytes,
                            :readOnlyRootFilesystem, :maxCommandCalls, :maxChangedFiles,
                            :maxSingleFileBytes, :maxWriteOperations, :maxWrittenBytes,
                            :maxDiffBytes, :maxTestRepairRounds, :overlayHash,
                            :createdAt, :createdBy, :updatedAt, :updatedBy
                        WHERE EXISTS (
                            SELECT 1 FROM crewscope.workspace_policy_overlay current
                             WHERE current.workspace_policy_id = :policyId
                               AND current.overlay_version = :expectedCurrentVersion
                               AND current.overlay_hash = :expectedCurrentHash
                               AND NOT EXISTS (
                                   SELECT 1 FROM crewscope.workspace_policy_overlay newer
                                    WHERE newer.workspace_policy_id = current.workspace_policy_id
                                      AND newer.overlay_version > current.overlay_version
                               )
                        )
                        """),
                parameters);
        if (inserted == 0) {
            throw new DomainValidationException(
                    "workspacePolicyOverlay.expectedCurrentOverlayHash",
                    "does not identify the current persisted Overlay");
        }
        return findByIdAndVersion(
                        base.scope().organizationId(),
                        base.scope().teamId(),
                        base.scope().projectId(),
                        required.id(),
                        required.version())
                .orElseThrow(() -> new AggregateNotFoundException("WorkspacePolicyOverlay", required.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspacePolicyOverlay> findCurrentByPolicy(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            WorkspacePolicyId policyId) {
        MapSqlParameterSource parameters = scopeParameters(organizationId, teamId, workProjectId)
                .addValue("policyId", Objects.requireNonNull(policyId).value());
        return jdbc.query(
                        """
                        SELECT id, overlay_version
                          FROM crewscope.workspace_policy_overlay
                         WHERE organization_id = :organizationId
                           AND team_id = :teamId
                           AND project_id = :projectId
                           AND workspace_policy_id = :policyId
                         ORDER BY overlay_version DESC
                         LIMIT 1
                        """,
                        parameters,
                        (row, ignored) -> new OverlayKey(
                                row.getObject("id", UUID.class), row.getLong("overlay_version")))
                .stream()
                .findFirst()
                .flatMap(key -> findByIdAndVersion(
                        organizationId,
                        teamId,
                        workProjectId,
                        new WorkspacePolicyOverlayId(key.id()),
                        key.version()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspacePolicyOverlay> findByIdAndVersion(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            WorkspacePolicyOverlayId overlayId,
            long version) {
        if (version < 1) {
            throw new DomainValidationException("workspacePolicyOverlay.version", "must be positive");
        }
        MapSqlParameterSource parameters = scopeParameters(organizationId, teamId, workProjectId)
                .addValue("id", Objects.requireNonNull(overlayId).value())
                .addValue("version", version);
        Optional<WorkspacePolicyId> policyId = jdbc.query(
                        """
                        SELECT workspace_policy_id
                          FROM crewscope.workspace_policy_overlay
                         WHERE organization_id = :organizationId
                           AND team_id = :teamId
                           AND project_id = :projectId
                           AND id = :id
                           AND overlay_version = :version
                        """,
                        parameters,
                        (row, ignored) -> new WorkspacePolicyId(
                                row.getObject("workspace_policy_id", UUID.class)))
                .stream()
                .findFirst();
        if (policyId.isEmpty()) {
            return Optional.empty();
        }
        WorkspacePolicy policy = findById(
                        organizationId, teamId, workProjectId, policyId.orElseThrow())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "WorkspacePolicy", policyId.orElseThrow()));
        return jdbc.query(
                        SELECT_OVERLAY + """
                         WHERE organization_id = :organizationId
                           AND team_id = :teamId
                           AND project_id = :projectId
                           AND id = :id
                           AND overlay_version = :version
                        """,
                        parameters,
                        (row, ignored) -> mapper.workspacePolicyOverlay(row, policy))
                .stream()
                .findFirst();
    }

    private WorkspacePolicy requirePolicy(WorkspacePolicyId policyId) {
        return jdbc.query(
                        SELECT_POLICY + " WHERE id = :id",
                        new MapSqlParameterSource("id", policyId.value()),
                        (row, ignored) -> mapper.workspacePolicy(row))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AggregateNotFoundException("WorkspacePolicy", policyId));
    }

    private static String overlayInsert(String source) {
        return """
                INSERT INTO crewscope.workspace_policy_overlay (
                    id, overlay_version, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, workspace_policy_id,
                    workspace_policy_hash, parent_overlay_hash, allowed_paths, command_catalog,
                    sandbox_network_mode, sandbox_cpu_count, sandbox_memory_mib, sandbox_pids,
                    sandbox_max_command_duration_seconds, sandbox_max_command_output_bytes,
                    sandbox_read_only_root_filesystem, max_command_calls, max_changed_files,
                    max_single_file_bytes, max_write_operations, max_written_bytes,
                    max_diff_bytes, max_test_repair_rounds, overlay_hash,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                )
                """ + ("VALUES".equals(source)
                ? """
                 VALUES (
                    :id, :overlayVersion, :organizationId, :teamId, :workspaceId, :projectId,
                    :taskId, :taskExecutionId, :attempt, :policyId, :policyHash,
                    :parentOverlayHash, CAST(:allowedPaths AS jsonb), CAST(:commandCatalog AS jsonb),
                    :networkMode, :cpuCount, :memoryMiB, :pids, :maxCommandDurationSeconds,
                    :maxCommandOutputBytes, :readOnlyRootFilesystem, :maxCommandCalls,
                    :maxChangedFiles, :maxSingleFileBytes, :maxWriteOperations,
                    :maxWrittenBytes, :maxDiffBytes, :maxTestRepairRounds, :overlayHash,
                    :createdAt, :createdBy, :updatedAt, :updatedBy
                 )
                """
                : source);
    }

    private MapSqlParameterSource policyParameters(WorkspacePolicy policy) {
        return basePolicyParameters(policy)
                .addValue("id", policy.id().value())
                .addValue("policySnapshotId", policy.policySnapshotId().value())
                .addValue("policySnapshotHash", policy.policySnapshotHash().value())
                .addValue("buildProfileKey", policy.buildProfile().key())
                .addValue("buildProfileVersion", policy.buildProfile().version())
                .addValue("buildProfileHash", policy.buildProfile().profileHash().value())
                .addValue("policyHash", policy.policyHash().value())
                .addValue("createdAt", CodingJdbcValue.timestamp(policy.createdAt()))
                .addValue("createdBy", policy.createdByPrincipalId().value());
    }

    private MapSqlParameterSource overlayParameters(
            WorkspacePolicyOverlay overlay, WorkspacePolicy policy) {
        PrincipalId createdBy = overlay.audit().createdBy().orElseThrow(() ->
                new DomainValidationException(
                        "workspacePolicyOverlay.createdByPrincipalId", "must identify a Principal"));
        PrincipalId updatedBy = overlay.audit().updatedBy().orElse(createdBy);
        return budgetParameters(
                        new MapSqlParameterSource()
                                .addValue("id", overlay.id().value())
                                .addValue("overlayVersion", overlay.version())
                                .addValue("organizationId", policy.scope().organizationId().value())
                                .addValue("teamId", policy.scope().teamId().value())
                                .addValue("workspaceId", policy.scope().workspaceId().value())
                                .addValue("projectId", policy.scope().projectId().value())
                                .addValue("taskId", policy.taskId().value())
                                .addValue("taskExecutionId", policy.taskExecutionId().value())
                                .addValue("attempt", policy.attempt())
                                .addValue("policyId", policy.id().value())
                                .addValue("policyHash", policy.policyHash().value())
                                .addValue("parentOverlayHash", overlay.parentOverlayHash()
                                        .map(TaskFactHash::value).orElse(null))
                                .addValue("allowedPaths", mapper.json(overlay.allowedPaths().values()))
                                .addValue("commandCatalog", catalogJson(overlay.commandCatalog().commands()))
                                .addValue("overlayHash", overlay.overlayHash().value())
                                .addValue("createdAt", CodingJdbcValue.timestamp(overlay.audit().createdAt()))
                                .addValue("createdBy", createdBy.value())
                                .addValue("updatedAt", CodingJdbcValue.timestamp(overlay.audit().updatedAt()))
                                .addValue("updatedBy", updatedBy.value()),
                        overlay.sandboxBudget(),
                        overlay.operationBudget());
    }

    private MapSqlParameterSource basePolicyParameters(WorkspacePolicy policy) {
        return budgetParameters(
                new MapSqlParameterSource()
                        .addValue("organizationId", policy.scope().organizationId().value())
                        .addValue("teamId", policy.scope().teamId().value())
                        .addValue("workspaceId", policy.scope().workspaceId().value())
                        .addValue("projectId", policy.scope().projectId().value())
                        .addValue("taskId", policy.taskId().value())
                        .addValue("taskExecutionId", policy.taskExecutionId().value())
                        .addValue("attempt", policy.attempt())
                        .addValue("codingTargetId", policy.codingTarget().snapshotId().value())
                        .addValue("codingTargetRevision", policy.codingTarget().revision())
                        .addValue("codingTargetHash", policy.codingTarget().snapshotHash().value())
                        .addValue("allowedPaths", mapper.json(policy.allowedPaths().values()))
                        .addValue("commandCatalog", catalogJson(policy.commandCatalog().commands())),
                policy.sandboxBudget(),
                policy.operationBudget());
    }

    private String catalogJson(Map<CommandKind, BuildCommand> commands) {
        return mapper.json(commands);
    }

    private static MapSqlParameterSource budgetParameters(
            MapSqlParameterSource parameters,
            io.crewscope.domain.coding.SandboxResourceBudget sandbox,
            io.crewscope.domain.coding.WorkspaceOperationBudget operations) {
        return parameters
                .addValue("networkMode", sandbox.networkMode().name())
                .addValue("cpuCount", sandbox.cpuCount())
                .addValue("memoryMiB", sandbox.memoryMiB())
                .addValue("pids", sandbox.pids())
                .addValue("maxCommandDurationSeconds", sandbox.maxCommandDurationSeconds())
                .addValue("maxCommandOutputBytes", sandbox.maxCommandOutputBytes())
                .addValue("readOnlyRootFilesystem", sandbox.readOnlyRootFilesystem())
                .addValue("maxCommandCalls", operations.maxCommandCalls())
                .addValue("maxChangedFiles", operations.maxChangedFiles())
                .addValue("maxSingleFileBytes", operations.maxSingleFileBytes())
                .addValue("maxWriteOperations", operations.maxWriteOperations())
                .addValue("maxWrittenBytes", operations.maxWrittenBytes())
                .addValue("maxDiffBytes", operations.maxDiffBytes())
                .addValue("maxTestRepairRounds", operations.maxTestRepairRounds());
    }

    private static MapSqlParameterSource scopeParameters(
            OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("teamId", Objects.requireNonNull(teamId).value())
                .addValue("projectId", Objects.requireNonNull(workProjectId).value());
    }

    private record OverlayKey(UUID id, long version) {}
}
