package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingCheckpoint;
import io.crewscope.domain.coding.CodingCheckpointId;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.EvidenceArtifactReference;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for immutable Diff, command, test and recovery checkpoint publications.
 *
 * <p>Root and normalized child rows are written in one transaction so partially published evidence
 * is never observable.
 */
@Component
public class JdbcCodingArtifactRepositoryAdapter {

    private static final String SELECT_DIFF = "SELECT * FROM crewscope.diff_artifact";
    private static final String SELECT_COMMAND = "SELECT * FROM crewscope.command_evidence";
    private static final String SELECT_TEST = "SELECT * FROM crewscope.test_evidence";
    private static final String SELECT_CHECKPOINT = "SELECT * FROM crewscope.coding_checkpoint";

    private final NamedParameterJdbcTemplate jdbc;
    private final CodingPersistenceMapper mapper;

    public JdbcCodingArtifactRepositoryAdapter(
            NamedParameterJdbcTemplate jdbc, CodingPersistenceMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Transactional
    public DiffArtifact createDiff(DiffArtifact artifact) {
        DiffArtifact required = Objects.requireNonNull(artifact, "artifact");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.diff_artifact (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, execution_workspace_id,
                        workspace_fingerprint, coding_target_snapshot_id,
                        coding_target_revision, coding_target_hash, baseline_commit,
                        delivery_commit, diff_generation, manifest_hash, file_count,
                        additions, deletions, patch_artifact_id, patch_artifact_kind,
                        patch_size_bytes, patch_sha256, final_hash,
                        created_at, created_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId,
                        :taskId, :taskExecutionId, :attempt, :executionWorkspaceId,
                        :workspaceFingerprint, :codingTargetId, :codingTargetRevision,
                        :codingTargetHash, :baselineCommit, :deliveryCommit,
                        :diffGeneration, :manifestHash, :fileCount, :additions, :deletions,
                        :patchArtifactId, 'DIFF_PATCH', :patchSizeBytes, :patchSha256,
                        :finalHash, :createdAt, :createdBy
                    )
                    """,
                    diffParameters(required));
            insertDiffFiles(required);
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.diffArtifact(failure, required);
        }
        return findDiffById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("DiffArtifact", required.id()));
    }

    @Transactional(readOnly = true)
    public Optional<DiffArtifact> findDiffById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            DiffArtifactId artifactId) {
        return queryDiffs(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND id = :id
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("id", Objects.requireNonNull(artifactId).value()))
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<DiffArtifact> findDiffByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return queryDiffs(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND execution_workspace_id = :workspaceId
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("workspaceId", Objects.requireNonNull(workspaceId).value()))
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<DiffArtifact> findDiffByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return queryDiffs(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND task_execution_id = :taskExecutionId
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("taskExecutionId", Objects.requireNonNull(taskExecutionId).value()))
                .stream()
                .findFirst();
    }

    @Transactional
    public CommandEvidence createCommand(CommandEvidence evidence) {
        CommandEvidence required = Objects.requireNonNull(evidence, "evidence");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.command_evidence (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, execution_workspace_id,
                        workspace_fingerprint, coding_target_snapshot_id,
                        coding_target_revision, coding_target_hash, evidence_sequence,
                        workspace_policy_id, workspace_policy_hash,
                        build_profile_key, build_profile_version, build_profile_hash,
                        command_kind, tool_key, argv, working_directory, timeout_seconds,
                        sandbox_image, command_spec_hash, started_at, finished_at,
                        termination, exit_code, summary, command_log_artifact_id,
                        command_log_kind, command_log_content_type, command_log_size_bytes,
                        command_log_content_hash, failure_classification, evidence_hash,
                        created_at, created_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId,
                        :taskId, :taskExecutionId, :attempt, :executionWorkspaceId,
                        :workspaceFingerprint, :codingTargetId, :codingTargetRevision,
                        :codingTargetHash, :evidenceSequence, :policyId, :policyHash,
                        :buildProfileKey, :buildProfileVersion, :buildProfileHash,
                        :commandKind, :toolKey, CAST(:argv AS jsonb), :workingDirectory,
                        :timeoutSeconds, :sandboxImage, :commandSpecHash, :startedAt,
                        :finishedAt, :termination, :exitCode, :summary, :commandLogArtifactId,
                        'COMMAND_LOG', :commandLogContentType, :commandLogSizeBytes,
                        :commandLogContentHash, :failureClassification, :evidenceHash,
                        :createdAt, :createdBy
                    )
                    """,
                    commandParameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.commandEvidence(failure, required);
        }
        return findCommandById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("CommandEvidence", required.id()));
    }

    @Transactional(readOnly = true)
    public Optional<CommandEvidence> findCommandById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            CommandEvidenceId evidenceId) {
        return queryCommands(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND id = :id
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("id", Objects.requireNonNull(evidenceId).value()))
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<CommandEvidence> findCommandsByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return queryCommands(
                """
                WHERE organization_id = :organizationId
                  AND team_id = :teamId
                  AND project_id = :projectId
                  AND task_execution_id = :taskExecutionId
                ORDER BY evidence_sequence ASC
                """,
                scopeParameters(organizationId, teamId, workProjectId)
                        .addValue("taskExecutionId", Objects.requireNonNull(taskExecutionId).value()));
    }

    @Transactional(readOnly = true)
    public List<CommandEvidence> findCommandsByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return queryCommands(
                """
                WHERE organization_id = :organizationId
                  AND team_id = :teamId
                  AND project_id = :projectId
                  AND execution_workspace_id = :workspaceId
                ORDER BY evidence_sequence ASC
                """,
                scopeParameters(organizationId, teamId, workProjectId)
                        .addValue("workspaceId", Objects.requireNonNull(workspaceId).value()));
    }

    @Transactional
    public TestEvidence createTest(TestEvidence evidence) {
        TestEvidence required = Objects.requireNonNull(evidence, "evidence");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.test_evidence (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, execution_workspace_id,
                        workspace_fingerprint, coding_target_snapshot_id,
                        coding_target_revision, coding_target_hash, diff_generation,
                        diff_manifest_hash, evidence_sequence, workspace_policy_id,
                        workspace_policy_hash, test_total, test_passed, test_failed,
                        test_errors, test_skipped, test_report_artifact_id, test_report_kind,
                        test_report_content_type, test_report_size_bytes,
                        test_report_content_hash, summary, failure_classification,
                        evidence_hash, created_at, created_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId,
                        :taskId, :taskExecutionId, :attempt, :executionWorkspaceId,
                        :workspaceFingerprint, :codingTargetId, :codingTargetRevision,
                        :codingTargetHash, :diffGeneration, :diffManifestHash,
                        :evidenceSequence, :policyId, :policyHash, :testTotal,
                        :testPassed, :testFailed, :testErrors, :testSkipped,
                        :testReportArtifactId, :testReportKind, :testReportContentType,
                        :testReportSizeBytes, :testReportContentHash, :summary,
                        :failureClassification, :evidenceHash, :createdAt, :createdBy
                    )
                    """,
                    testParameters(required));
            insertTestChildren(required);
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.testEvidence(failure, required);
        }
        return findTestById(
                        required.scope().organizationId(),
                        required.scope().teamId(),
                        required.scope().projectId(),
                        required.id())
                .orElseThrow(() -> new AggregateNotFoundException("TestEvidence", required.id()));
    }

    @Transactional(readOnly = true)
    public Optional<TestEvidence> findTestById(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TestEvidenceId evidenceId) {
        return queryTests(
                        """
                        WHERE organization_id = :organizationId
                          AND team_id = :teamId
                          AND project_id = :projectId
                          AND id = :id
                        """,
                        scopeParameters(organizationId, teamId, workProjectId)
                                .addValue("id", Objects.requireNonNull(evidenceId).value()))
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<TestEvidence> findTestsByTaskExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            TaskExecutionId taskExecutionId) {
        return queryTests(
                """
                WHERE organization_id = :organizationId
                  AND team_id = :teamId
                  AND project_id = :projectId
                  AND task_execution_id = :taskExecutionId
                ORDER BY evidence_sequence ASC
                """,
                scopeParameters(organizationId, teamId, workProjectId)
                        .addValue("taskExecutionId", Objects.requireNonNull(taskExecutionId).value()));
    }

    @Transactional(readOnly = true)
    public List<TestEvidence> findTestsByWorkspace(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId workProjectId,
            ExecutionWorkspaceId workspaceId) {
        return queryTests(
                """
                WHERE organization_id = :organizationId
                  AND team_id = :teamId
                  AND project_id = :projectId
                  AND execution_workspace_id = :workspaceId
                ORDER BY evidence_sequence ASC
                """,
                scopeParameters(organizationId, teamId, workProjectId)
                        .addValue("workspaceId", Objects.requireNonNull(workspaceId).value()));
    }

    @Transactional
    public CodingCheckpoint appendCheckpoint(CodingCheckpoint checkpoint) {
        CodingCheckpoint required = Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.coding_checkpoint (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, coding_target_snapshot_id,
                        coding_target_revision, coding_target_hash, execution_workspace_id,
                        workspace_fingerprint, workspace_policy_id, workspace_policy_hash,
                        agent_run_id, agent_run_sequence, segment_sequence,
                        plan_version_id, plan_version_hash, step_execution_id,
                        plan_markdown, todos, work_state_hash, diff_generation,
                        diff_manifest_hash, test_evidence_id, test_evidence_hash,
                        agent_state_snapshot_id, snapshot_sequence, snapshot_content_hash,
                        checkpoint_sequence, checkpoint_hash, created_at,
                        created_by_principal_id
                    ) VALUES (
                        :id, :organizationId, :teamId, :workspaceId, :projectId,
                        :taskId, :taskExecutionId, :attempt, :codingTargetId,
                        :codingTargetRevision, :codingTargetHash, :executionWorkspaceId,
                        :workspaceFingerprint, :policyId, :policyHash, :agentRunId,
                        :agentRunSequence, :segmentSequence, :planVersionId,
                        :planVersionHash, :stepExecutionId, :planMarkdown,
                        CAST(:todos AS jsonb), :workStateHash, :diffGeneration,
                        :diffManifestHash, :testEvidenceId, :testEvidenceHash,
                        :agentStateSnapshotId, :snapshotSequence, :snapshotContentHash,
                        :checkpointSequence, :checkpointHash, :createdAt, :createdBy
                    )
                    """,
                    checkpointParameters(required));
        } catch (DataIntegrityViolationException failure) {
            throw CodingPersistenceConflictMapper.codingCheckpoint(failure, required);
        }
        return findCheckpointById(required.scope().organizationId(), required.id())
                .orElseThrow(() -> new AggregateNotFoundException("CodingCheckpoint", required.id()));
    }

    @Transactional(readOnly = true)
    public Optional<CodingCheckpoint> findCheckpointById(
            OrganizationId organizationId, CodingCheckpointId checkpointId) {
        return jdbc.query(
                        SELECT_CHECKPOINT + """
                         WHERE organization_id = :organizationId AND id = :id
                        """,
                        new MapSqlParameterSource()
                                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                                .addValue("id", Objects.requireNonNull(checkpointId).value()),
                        (row, ignored) -> mapper.codingCheckpoint(row))
                .stream()
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<CodingCheckpoint> findLatestCheckpointByWorkspace(
            OrganizationId organizationId, ExecutionWorkspaceId executionWorkspaceId) {
        return jdbc.query(
                        SELECT_CHECKPOINT + """
                         WHERE organization_id = :organizationId
                           AND execution_workspace_id = :workspaceId
                         ORDER BY checkpoint_sequence DESC
                         LIMIT 1
                        """,
                        new MapSqlParameterSource()
                                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                                .addValue("workspaceId", Objects.requireNonNull(executionWorkspaceId).value()),
                        (row, ignored) -> mapper.codingCheckpoint(row))
                .stream()
                .findFirst();
    }

    private List<DiffArtifact> queryDiffs(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
                SELECT_DIFF + " " + predicate,
                parameters,
                (row, ignored) -> {
                    UUID id = row.getObject("id", UUID.class);
                    return mapper.diffArtifact(row, loadDiffFiles(id));
                });
    }

    private List<CommandEvidence> queryCommands(
            String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
                SELECT_COMMAND + " " + predicate,
                parameters,
                (row, ignored) -> mapper.commandEvidence(row));
    }

    private List<TestEvidence> queryTests(String predicate, MapSqlParameterSource parameters) {
        return jdbc.query(
                SELECT_TEST + " " + predicate,
                parameters,
                (row, ignored) -> {
                    UUID id = row.getObject("id", UUID.class);
                    List<CommandEvidenceReference> commands = loadTestCommands(id);
                    List<AcceptanceResult> acceptance = loadAcceptanceResults(id);
                    return mapper.testEvidence(row, commands, acceptance);
                });
    }

    private List<DiffFileEntry> loadDiffFiles(UUID artifactId) {
        return jdbc.query(
                """
                SELECT * FROM crewscope.diff_file_entry
                 WHERE diff_artifact_id = :artifactId
                 ORDER BY ordinal ASC
                """,
                new MapSqlParameterSource("artifactId", artifactId),
                (row, ignored) -> mapper.diffFile(row));
    }

    private List<CommandEvidenceReference> loadTestCommands(UUID testEvidenceId) {
        return jdbc.query(
                commandReferenceSql(
                        """
                        FROM crewscope.test_evidence_command link
                        JOIN crewscope.command_evidence command
                          ON command.id = link.command_evidence_id
                        WHERE link.test_evidence_id = :testEvidenceId
                        ORDER BY link.ordinal ASC
                        """),
                new MapSqlParameterSource("testEvidenceId", testEvidenceId),
                (row, ignored) -> mapper.commandReference(row));
    }

    private List<AcceptanceResult> loadAcceptanceResults(UUID testEvidenceId) {
        List<AcceptanceRow> rows = jdbc.query(
                """
                SELECT criterion_index, criterion, status, summary
                  FROM crewscope.test_acceptance_result
                 WHERE test_evidence_id = :testEvidenceId
                 ORDER BY criterion_index ASC
                """,
                new MapSqlParameterSource("testEvidenceId", testEvidenceId),
                (row, ignored) -> new AcceptanceRow(
                        row.getInt("criterion_index"),
                        row.getString("criterion"),
                        AcceptanceStatus.valueOf(row.getString("status")),
                        row.getString("summary")));
        List<AcceptanceResult> results = new ArrayList<>(rows.size());
        for (AcceptanceRow row : rows) {
            List<CommandEvidenceReference> references = jdbc.query(
                    commandReferenceSql(
                            """
                            FROM crewscope.test_acceptance_evidence link
                            JOIN crewscope.command_evidence command
                              ON command.id = link.command_evidence_id
                            WHERE link.test_evidence_id = :testEvidenceId
                              AND link.criterion_index = :criterionIndex
                            ORDER BY link.ordinal ASC
                            """),
                    new MapSqlParameterSource()
                            .addValue("testEvidenceId", testEvidenceId)
                            .addValue("criterionIndex", row.index()),
                    (result, ignored) -> mapper.commandReference(result));
            results.add(new AcceptanceResult(
                    row.index(),
                    row.criterion(),
                    row.status(),
                    references,
                    new EvidenceSummary(row.summary())));
        }
        return List.copyOf(results);
    }

    private static String commandReferenceSql(String suffix) {
        return """
                SELECT command.id AS command_evidence_id,
                       command.evidence_sequence AS command_evidence_sequence,
                       command.evidence_hash AS command_evidence_hash,
                       command.failure_classification AS command_failure_classification
                """ + suffix;
    }

    private void insertDiffFiles(DiffArtifact artifact) {
        List<DiffFileEntry> files = artifact.manifest().files();
        SqlParameterSource[] batches = new SqlParameterSource[files.size()];
        for (int index = 0; index < files.size(); index++) {
            DiffFileEntry file = files.get(index);
            batches[index] = new MapSqlParameterSource()
                    .addValue("artifactId", artifact.id().value())
                    .addValue("ordinal", index)
                    .addValue("path", file.path().value())
                    .addValue("oldPath", file.oldPath().map(value -> value.value()).orElse(null))
                    .addValue("kind", file.kind().name())
                    .addValue("additions", file.additions())
                    .addValue("deletions", file.deletions())
                    .addValue("binary", file.binary())
                    .addValue("patchTruncated", file.patchTruncated())
                    .addValue("patchSha256", file.patchSha256().value())
                    .addValue("patchPreview", file.patchPreview().orElse(null));
        }
        if (batches.length > 0) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO crewscope.diff_file_entry (
                        diff_artifact_id, ordinal, path, old_path, change_kind,
                        additions, deletions, is_binary, patch_truncated,
                        patch_sha256, patch_preview
                    ) VALUES (
                        :artifactId, :ordinal, :path, :oldPath, :kind,
                        :additions, :deletions, :binary, :patchTruncated,
                        :patchSha256, :patchPreview
                    )
                    """,
                    batches);
        }
    }

    private void insertTestChildren(TestEvidence evidence) {
        insertTestCommands(evidence);
        for (AcceptanceResult result : evidence.acceptanceResults()) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.test_acceptance_result (
                        test_evidence_id, criterion_index, criterion, status, summary
                    ) VALUES (:testEvidenceId, :criterionIndex, :criterion, :status, :summary)
                    """,
                    new MapSqlParameterSource()
                            .addValue("testEvidenceId", evidence.id().value())
                            .addValue("criterionIndex", result.criterionIndex())
                            .addValue("criterion", result.criterion())
                            .addValue("status", result.status().name())
                            .addValue("summary", result.summary().value()));
            SqlParameterSource[] references = new SqlParameterSource[result.evidence().size()];
            for (int index = 0; index < result.evidence().size(); index++) {
                references[index] = new MapSqlParameterSource()
                        .addValue("testEvidenceId", evidence.id().value())
                        .addValue("criterionIndex", result.criterionIndex())
                        .addValue("ordinal", index)
                        .addValue("commandEvidenceId", result.evidence().get(index).id().value());
            }
            if (references.length > 0) {
                jdbc.batchUpdate(
                        """
                        INSERT INTO crewscope.test_acceptance_evidence (
                            test_evidence_id, criterion_index, ordinal, command_evidence_id
                        ) VALUES (
                            :testEvidenceId, :criterionIndex, :ordinal, :commandEvidenceId
                        )
                        """,
                        references);
            }
        }
    }

    private void insertTestCommands(TestEvidence evidence) {
        SqlParameterSource[] commands = new SqlParameterSource[evidence.commands().size()];
        for (int index = 0; index < evidence.commands().size(); index++) {
            commands[index] = scopeParameters(evidence)
                    .addValue("testEvidenceId", evidence.id().value())
                    .addValue("ordinal", index)
                    .addValue("commandEvidenceId", evidence.commands().get(index).id().value());
        }
        if (commands.length > 0) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO crewscope.test_evidence_command (
                        test_evidence_id, ordinal, organization_id, team_id,
                        workspace_id, project_id, task_id, task_execution_id,
                        command_evidence_id
                    ) VALUES (
                        :testEvidenceId, :ordinal, :organizationId, :teamId,
                        :workspaceId, :projectId, :taskId, :taskExecutionId,
                        :commandEvidenceId
                    )
                    """,
                    commands);
        }
    }

    private MapSqlParameterSource diffParameters(DiffArtifact artifact) {
        return scopeParameters(artifact)
                .addValue("id", artifact.id().value())
                .addValue("attempt", artifact.attempt())
                .addValue("executionWorkspaceId", artifact.executionWorkspaceId().value())
                .addValue("workspaceFingerprint", requireWorkspaceFingerprint(artifact.executionWorkspaceId()))
                .addValue("codingTargetId", artifact.codingTarget().snapshotId().value())
                .addValue("codingTargetRevision", artifact.codingTarget().revision())
                .addValue("codingTargetHash", artifact.codingTarget().snapshotHash().value())
                .addValue("baselineCommit", artifact.baselineCommit().value())
                .addValue("deliveryCommit", artifact.deliveryCommit().value())
                .addValue("diffGeneration", artifact.manifest().generation().value())
                .addValue("manifestHash", artifact.manifest().contentHash().value())
                .addValue("fileCount", artifact.manifest().fileCount())
                .addValue("additions", artifact.manifest().additions())
                .addValue("deletions", artifact.manifest().deletions())
                .addValue("patchArtifactId", artifact.patchArtifact().artifactId().value())
                .addValue("patchSizeBytes", artifact.patchArtifact().sizeBytes())
                .addValue("patchSha256", artifact.patchArtifact().patchSha256().value())
                .addValue("finalHash", artifact.finalHash().value())
                .addValue("createdAt", CodingJdbcValue.timestamp(artifact.audit().createdAt()))
                .addValue("createdBy", createdBy(artifact.audit(), "diffArtifact").value());
    }

    private MapSqlParameterSource commandParameters(CommandEvidence evidence) {
        EvidenceArtifactReference log = evidence.commandLog();
        return scopeParameters(evidence)
                .addValue("id", evidence.id().value())
                .addValue("attempt", evidence.attempt())
                .addValue("executionWorkspaceId", evidence.executionWorkspaceId().value())
                .addValue("workspaceFingerprint", evidence.workspaceFingerprint().value())
                .addValue("codingTargetId", evidence.codingTarget().snapshotId().value())
                .addValue("codingTargetRevision", evidence.codingTarget().revision())
                .addValue("codingTargetHash", evidence.codingTarget().snapshotHash().value())
                .addValue("evidenceSequence", evidence.sequence().value())
                .addValue("policyId", evidence.workspacePolicy().id().value())
                .addValue("policyHash", evidence.workspacePolicy().policyHash().value())
                .addValue("buildProfileKey", evidence.commandSpec().buildProfile().key())
                .addValue("buildProfileVersion", evidence.commandSpec().buildProfile().version())
                .addValue("buildProfileHash", evidence.commandSpec().buildProfile().profileHash().value())
                .addValue("commandKind", evidence.commandSpec().commandKind().name())
                .addValue("toolKey", evidence.commandSpec().toolKey())
                .addValue("argv", mapper.json(evidence.commandSpec().argv()))
                .addValue("workingDirectory", evidence.commandSpec().workingDirectory())
                .addValue("timeoutSeconds", evidence.commandSpec().timeoutSeconds())
                .addValue("sandboxImage", evidence.commandSpec().sandboxImage().value())
                .addValue("commandSpecHash", evidence.commandSpec().specHash().value())
                .addValue("startedAt", CodingJdbcValue.timestamp(evidence.startedAt()))
                .addValue("finishedAt", CodingJdbcValue.timestamp(evidence.finishedAt()))
                .addValue("termination", evidence.termination().name())
                .addValue("exitCode", evidence.exitCode().orElse(null))
                .addValue("summary", evidence.summary().value())
                .addValue("commandLogArtifactId", log.artifactId().value())
                .addValue("commandLogContentType", log.contentType())
                .addValue("commandLogSizeBytes", log.sizeBytes())
                .addValue("commandLogContentHash", log.contentHash().value())
                .addValue("failureClassification", evidence.failureClassification()
                        .map(Enum::name).orElse(null))
                .addValue("evidenceHash", evidence.evidenceHash().value())
                .addValue("createdAt", CodingJdbcValue.timestamp(evidence.audit().createdAt()))
                .addValue("createdBy", createdBy(evidence.audit(), "commandEvidence").value());
    }

    private MapSqlParameterSource testParameters(TestEvidence evidence) {
        EvidenceArtifactReference report = evidence.testReport().orElse(null);
        return scopeParameters(evidence)
                .addValue("id", evidence.id().value())
                .addValue("attempt", evidence.attempt())
                .addValue("executionWorkspaceId", evidence.executionWorkspaceId().value())
                .addValue("workspaceFingerprint", evidence.workspaceFingerprint().value())
                .addValue("codingTargetId", evidence.codingTarget().snapshotId().value())
                .addValue("codingTargetRevision", evidence.codingTarget().revision())
                .addValue("codingTargetHash", evidence.codingTarget().snapshotHash().value())
                .addValue("diffGeneration", evidence.diffGeneration().value())
                .addValue("diffManifestHash", evidence.diffManifestHash().value())
                .addValue("evidenceSequence", evidence.sequence().value())
                .addValue("policyId", evidence.workspacePolicy().id().value())
                .addValue("policyHash", evidence.workspacePolicy().policyHash().value())
                .addValue("testTotal", evidence.statistics().total())
                .addValue("testPassed", evidence.statistics().passed())
                .addValue("testFailed", evidence.statistics().failed())
                .addValue("testErrors", evidence.statistics().errors())
                .addValue("testSkipped", evidence.statistics().skipped())
                .addValue("testReportArtifactId", report == null ? null : report.artifactId().value())
                .addValue("testReportKind", report == null ? null : report.kind().name())
                .addValue("testReportContentType", report == null ? null : report.contentType())
                .addValue("testReportSizeBytes", report == null ? null : report.sizeBytes())
                .addValue("testReportContentHash", report == null ? null : report.contentHash().value())
                .addValue("summary", evidence.summary().value())
                .addValue("failureClassification", evidence.failureClassification()
                        .map(Enum::name).orElse(null))
                .addValue("evidenceHash", evidence.evidenceHash().value())
                .addValue("createdAt", CodingJdbcValue.timestamp(evidence.audit().createdAt()))
                .addValue("createdBy", createdBy(evidence.audit(), "testEvidence").value());
    }

    private MapSqlParameterSource checkpointParameters(CodingCheckpoint checkpoint) {
        return scopeParameters(checkpoint)
                .addValue("id", checkpoint.id().value())
                .addValue("attempt", checkpoint.attempt())
                .addValue("codingTargetId", checkpoint.codingTarget().snapshotId().value())
                .addValue("codingTargetRevision", checkpoint.codingTarget().revision())
                .addValue("codingTargetHash", checkpoint.codingTarget().snapshotHash().value())
                .addValue("executionWorkspaceId", checkpoint.executionWorkspaceId().value())
                .addValue("workspaceFingerprint", checkpoint.workspaceFingerprint().value())
                .addValue("policyId", checkpoint.workspacePolicy().id().value())
                .addValue("policyHash", checkpoint.workspacePolicy().policyHash().value())
                .addValue("agentRunId", checkpoint.agentRunId().value())
                .addValue("agentRunSequence", checkpoint.agentRunSequence())
                .addValue("segmentSequence", checkpoint.segmentSequence())
                .addValue("planVersionId", checkpoint.planVersionId()
                        .map(value -> value.value()).orElse(null))
                .addValue("planVersionHash", checkpoint.planVersionHash()
                        .map(value -> value.value()).orElse(null))
                .addValue("stepExecutionId", checkpoint.stepExecutionId()
                        .map(value -> value.value()).orElse(null))
                .addValue("planMarkdown", checkpoint.workState().planMarkdown())
                .addValue("todos", mapper.json(checkpoint.workState().todos()))
                .addValue("workStateHash", checkpoint.workState().contentHash().value())
                .addValue("diffGeneration", checkpoint.diffGeneration().value())
                .addValue("diffManifestHash", checkpoint.diffManifestHash().value())
                .addValue("testEvidenceId", checkpoint.testEvidenceId()
                        .map(value -> value.value()).orElse(null))
                .addValue("testEvidenceHash", checkpoint.testEvidenceHash()
                        .map(value -> value.value()).orElse(null))
                .addValue("agentStateSnapshotId", checkpoint.agentStateSnapshotId().value())
                .addValue("snapshotSequence", checkpoint.snapshotSequence())
                .addValue("snapshotContentHash", checkpoint.snapshotContentHash().value())
                .addValue("checkpointSequence", checkpoint.checkpointSequence())
                .addValue("checkpointHash", checkpoint.checkpointHash().value())
                .addValue("createdAt", CodingJdbcValue.timestamp(checkpoint.audit().createdAt()))
                .addValue("createdBy", createdBy(checkpoint.audit(), "codingCheckpoint").value());
    }

    /** DiffArtifact omits the fingerprint from its domain API; V14 closes it from the Workspace. */
    private String requireWorkspaceFingerprint(ExecutionWorkspaceId workspaceId) {
        return jdbc.query(
                        """
                        SELECT workspace_fingerprint FROM crewscope.execution_workspace
                         WHERE id = :id
                        """,
                        new MapSqlParameterSource("id", workspaceId.value()),
                        (row, ignored) -> row.getString("workspace_fingerprint"))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AggregateNotFoundException("ExecutionWorkspace", workspaceId));
    }

    private static PrincipalId createdBy(
            io.crewscope.domain.shared.audit.AuditMetadata audit, String aggregate) {
        return audit.createdBy().orElseThrow(() -> new DomainValidationException(
                aggregate + ".createdByPrincipalId", "must identify a Principal"));
    }

    private static MapSqlParameterSource scopeParameters(DiffArtifact value) {
        return scopeParameters(
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.taskId().value(),
                value.taskExecutionId().value());
    }

    private static MapSqlParameterSource scopeParameters(CommandEvidence value) {
        return scopeParameters(
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.taskId().value(),
                value.taskExecutionId().value());
    }

    private static MapSqlParameterSource scopeParameters(TestEvidence value) {
        return scopeParameters(
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.taskId().value(),
                value.taskExecutionId().value());
    }

    private static MapSqlParameterSource scopeParameters(CodingCheckpoint value) {
        return scopeParameters(
                value.scope().organizationId().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.taskId().value(),
                value.taskExecutionId().value());
    }

    private static MapSqlParameterSource scopeParameters(
            UUID organizationId,
            UUID teamId,
            UUID workspaceId,
            UUID projectId,
            UUID taskId,
            UUID taskExecutionId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("teamId", teamId)
                .addValue("workspaceId", workspaceId)
                .addValue("projectId", projectId)
                .addValue("taskId", taskId)
                .addValue("taskExecutionId", taskExecutionId);
    }

    private static MapSqlParameterSource scopeParameters(
            OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("teamId", Objects.requireNonNull(teamId).value())
                .addValue("projectId", Objects.requireNonNull(workProjectId).value());
    }

    private record AcceptanceRow(
            int index, String criterion, AcceptanceStatus status, String summary) {}
}
