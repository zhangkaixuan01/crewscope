package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.application.coding.query.CodingAttemptProjection;
import io.crewscope.application.coding.query.CodingAttemptProjection.ArtifactSummary;
import io.crewscope.application.coding.query.CodingAttemptProjection.CodingResultSummary;
import io.crewscope.application.coding.query.CodingAttemptProjection.DiffFileSummary;
import io.crewscope.application.coding.query.CodingAttemptProjection.DiffManifestSummary;
import io.crewscope.application.coding.query.CodingAttemptProjection.SandboxSummary;
import io.crewscope.application.coding.query.CodingAttemptProjection.WorkspaceSummary;
import io.crewscope.application.coding.query.CodingAttemptQueryPort;
import io.crewscope.application.coding.query.CodingEvidenceCursor;
import io.crewscope.application.coding.query.CodingEvidencePage;
import io.crewscope.application.coding.query.CommandEvidenceProjection;
import io.crewscope.application.coding.query.TestEvidenceProjection;
import io.crewscope.application.coding.query.TestEvidenceProjection.AcceptanceProjection;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Fixed-query-count JDBC read model for Coding attempt APIs. */
@Repository
public class JdbcCodingAttemptQueryAdapter implements CodingAttemptQueryPort {

    private static final String ATTEMPT_SELECT = """
            WITH command_counts AS (
                SELECT task_execution_id, COUNT(*) AS command_count
                  FROM crewscope.command_evidence
                 WHERE organization_id = :organizationId AND team_id = :teamId
                   AND project_id = :projectId AND task_id = :taskId
                 GROUP BY task_execution_id
            ), test_counts AS (
                SELECT task_execution_id, COUNT(*) AS test_count
                  FROM crewscope.test_evidence
                 WHERE organization_id = :organizationId AND team_id = :teamId
                   AND project_id = :projectId AND task_id = :taskId
                 GROUP BY task_execution_id
            ), successful_test AS (
                SELECT DISTINCT ON (task_execution_id, diff_generation, diff_manifest_hash)
                       task_execution_id, id, evidence_hash, diff_generation,
                       diff_manifest_hash, created_at
                  FROM crewscope.test_evidence
                 WHERE organization_id = :organizationId AND team_id = :teamId
                   AND project_id = :projectId AND task_id = :taskId
                   AND failure_classification IS NULL
                 ORDER BY task_execution_id, diff_generation, diff_manifest_hash,
                          evidence_sequence DESC, id DESC
            )
            SELECT ew.id AS ew_id, ew.task_execution_id, ew.attempt,
                   ew.repository_key, ew.baseline_commit, ew.managed_branch,
                   ew.status AS ew_status, ew.recovery_generation,
                   ew.completion_reason, ew.failure_code, ew.workspace_fingerprint,
                   ew.coding_target_snapshot_id, ew.coding_target_revision,
                   ew.coding_target_hash, ew.version AS ew_version, ew.retain_until,
                   ew.created_at AS ew_created_at, ew.updated_at AS ew_updated_at,
                   wp.id AS policy_id, wp.sandbox_network_mode, wp.sandbox_cpu_count,
                   wp.sandbox_memory_mib, wp.sandbox_pids,
                   wp.sandbox_max_command_duration_seconds,
                   wp.sandbox_max_command_output_bytes,
                   wp.sandbox_read_only_root_filesystem, wp.max_command_calls,
                   wp.max_changed_files, wp.max_single_file_bytes,
                   wp.max_write_operations, wp.max_written_bytes, wp.max_diff_bytes,
                   wp.max_test_repair_rounds, wp.build_profile_key,
                   wp.build_profile_version,
                   da.id AS diff_id, da.diff_generation, da.manifest_hash,
                   da.file_count, da.additions, da.deletions,
                   da.baseline_commit AS diff_baseline_commit,
                   da.delivery_commit, da.final_hash AS diff_final_hash,
                   da.patch_artifact_id, da.patch_artifact_kind,
                   pa.content_type AS patch_content_type,
                   da.patch_size_bytes, da.patch_sha256, da.created_at AS diff_created_at,
                   COALESCE(cc.command_count, 0) AS command_count,
                   COALESCE(tc.test_count, 0) AS test_count,
                   st.id AS result_test_id, st.evidence_hash AS result_test_hash,
                   st.created_at AS result_completed_at
              FROM crewscope.execution_workspace ew
              LEFT JOIN crewscope.workspace_policy wp ON wp.task_execution_id = ew.task_execution_id
              LEFT JOIN crewscope.diff_artifact da ON da.execution_workspace_id = ew.id
              LEFT JOIN crewscope.runtime_artifact pa ON pa.artifact_id = da.patch_artifact_id
              LEFT JOIN command_counts cc ON cc.task_execution_id = ew.task_execution_id
              LEFT JOIN test_counts tc ON tc.task_execution_id = ew.task_execution_id
              LEFT JOIN successful_test st ON st.task_execution_id = ew.task_execution_id
                  AND st.diff_generation = da.diff_generation
                  AND st.diff_manifest_hash = da.manifest_hash
             WHERE ew.organization_id = :organizationId AND ew.team_id = :teamId
               AND ew.project_id = :projectId AND ew.task_id = :taskId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCodingAttemptQueryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true)
    public List<CodingAttemptProjection> findByTask(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId) {
        MapSqlParameterSource parameters = scope(organizationId, teamId, projectId, taskId);
        List<AttemptRow> rows = jdbc.query(
                ATTEMPT_SELECT + " ORDER BY ew.attempt ASC, ew.id ASC",
                parameters,
                (result, ignored) -> attemptRow(result));
        return attachDiffFiles(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CodingAttemptProjection> findByExecution(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId) {
        MapSqlParameterSource parameters = scope(organizationId, teamId, projectId, taskId)
                .addValue("executionId", Objects.requireNonNull(executionId, "executionId").value());
        List<AttemptRow> rows = jdbc.query(
                ATTEMPT_SELECT + " AND ew.task_execution_id = :executionId",
                parameters,
                (result, ignored) -> attemptRow(result));
        return attachDiffFiles(rows).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public CodingEvidencePage<CommandEvidenceProjection> findCommands(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit) {
        requireLimit(limit);
        MapSqlParameterSource parameters = evidenceScope(
                organizationId, teamId, projectId, taskId, executionId, cursor, limit);
        List<CommandEvidenceProjection> values = jdbc.query(
                """
                SELECT id, evidence_sequence, command_kind, tool_key, timeout_seconds,
                       started_at, finished_at, termination, exit_code, summary,
                       failure_classification, evidence_hash,
                       command_log_artifact_id, command_log_kind,
                       command_log_content_type, command_log_size_bytes,
                       command_log_content_hash
                  FROM crewscope.command_evidence
                 WHERE organization_id = :organizationId AND team_id = :teamId
                   AND project_id = :projectId AND task_id = :taskId
                   AND task_execution_id = :executionId
                   AND (CAST(:cursorSequence AS BIGINT) IS NULL
                        OR (evidence_sequence, id) >
                           (CAST(:cursorSequence AS BIGINT), CAST(:cursorId AS UUID)))
                 ORDER BY evidence_sequence ASC, id ASC
                 LIMIT :fetchLimit
                """,
                parameters,
                (result, ignored) -> new CommandEvidenceProjection(
                        uuid(result, "id"),
                        result.getLong("evidence_sequence"),
                        result.getString("command_kind"),
                        result.getString("tool_key"),
                        result.getInt("timeout_seconds"),
                        instant(result, "started_at"),
                        instant(result, "finished_at"),
                        result.getString("termination"),
                        nullableInt(result, "exit_code"),
                        result.getString("summary"),
                        result.getString("failure_classification"),
                        result.getString("evidence_hash"),
                        new ArtifactSummary(
                                uuid(result, "command_log_artifact_id"),
                                result.getString("command_log_kind"),
                                result.getString("command_log_content_type"),
                                result.getLong("command_log_size_bytes"),
                                result.getString("command_log_content_hash"))));
        return page(values, limit, value -> new CodingEvidenceCursor(value.sequence(), value.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public CodingEvidencePage<TestEvidenceProjection> findTestEvidence(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit) {
        requireLimit(limit);
        MapSqlParameterSource parameters = evidenceScope(
                organizationId, teamId, projectId, taskId, executionId, cursor, limit);
        List<TestRow> rows = jdbc.query(
                """
                SELECT id, evidence_sequence, diff_generation, diff_manifest_hash,
                       test_total, test_passed, test_failed, test_errors, test_skipped,
                       summary, failure_classification, evidence_hash,
                       test_report_artifact_id, test_report_kind,
                       test_report_content_type, test_report_size_bytes,
                       test_report_content_hash, created_at
                  FROM crewscope.test_evidence
                 WHERE organization_id = :organizationId AND team_id = :teamId
                   AND project_id = :projectId AND task_id = :taskId
                   AND task_execution_id = :executionId
                   AND (CAST(:cursorSequence AS BIGINT) IS NULL
                        OR (evidence_sequence, id) >
                           (CAST(:cursorSequence AS BIGINT), CAST(:cursorId AS UUID)))
                 ORDER BY evidence_sequence ASC, id ASC
                 LIMIT :fetchLimit
                """,
                parameters,
                (result, ignored) -> testRow(result));
        List<TestRow> retained = rows.size() > limit ? rows.subList(0, limit) : rows;
        enrichTests(retained);
        List<TestEvidenceProjection> items = retained.stream().map(TestRow::projection).toList();
        Optional<CodingEvidenceCursor> next = rows.size() > limit
                ? Optional.of(new CodingEvidenceCursor(
                        retained.get(retained.size() - 1).sequence,
                        retained.get(retained.size() - 1).id))
                : Optional.empty();
        return new CodingEvidencePage<>(items, next);
    }

    private List<CodingAttemptProjection> attachDiffFiles(List<AttemptRow> rows) {
        List<UUID> diffIds = rows.stream()
                .map(row -> row.diffArtifactId)
                .flatMap(Optional::stream)
                .toList();
        Map<UUID, List<DiffFileSummary>> files = new HashMap<>();
        if (!diffIds.isEmpty()) {
            jdbc.query(
                    """
                    SELECT diff_artifact_id, ordinal, path, old_path, change_kind,
                           additions, deletions, is_binary, patch_truncated,
                           patch_sha256
                      FROM crewscope.diff_file_entry
                     WHERE diff_artifact_id IN (:diffIds)
                     ORDER BY diff_artifact_id, ordinal
                    """,
                    new MapSqlParameterSource("diffIds", diffIds),
                    (RowCallbackHandler) result -> files.computeIfAbsent(uuid(result, "diff_artifact_id"), ignored -> new ArrayList<>())
                            .add(new DiffFileSummary(
                                    result.getInt("ordinal"),
                                    result.getString("path"),
                                    result.getString("old_path"),
                                    result.getString("change_kind"),
                                    result.getLong("additions"),
                                    result.getLong("deletions"),
                                    result.getBoolean("is_binary"),
                                    result.getBoolean("patch_truncated"),
                                    result.getString("patch_sha256"))));
        }
        return rows.stream().map(row -> row.projection(files)).toList();
    }

    private void enrichTests(List<TestRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<UUID> ids = rows.stream().map(row -> row.id).toList();
        Map<UUID, List<UUID>> commands = new HashMap<>();
        jdbc.query(
                """
                SELECT test_evidence_id, command_evidence_id
                  FROM crewscope.test_evidence_command
                 WHERE test_evidence_id IN (:ids)
                 ORDER BY test_evidence_id, ordinal
                """,
                new MapSqlParameterSource("ids", ids),
                (RowCallbackHandler) result -> commands.computeIfAbsent(uuid(result, "test_evidence_id"), ignored -> new ArrayList<>())
                        .add(uuid(result, "command_evidence_id")));

        Map<UUID, Map<Integer, AcceptanceBuilder>> acceptance = new HashMap<>();
        jdbc.query(
                """
                SELECT result.test_evidence_id, result.criterion_index,
                       result.criterion, result.status, result.summary,
                       evidence.command_evidence_id
                  FROM crewscope.test_acceptance_result result
                  LEFT JOIN crewscope.test_acceptance_evidence evidence
                    ON evidence.test_evidence_id = result.test_evidence_id
                   AND evidence.criterion_index = result.criterion_index
                 WHERE result.test_evidence_id IN (:ids)
                 ORDER BY result.test_evidence_id, result.criterion_index, evidence.ordinal
                """,
                new MapSqlParameterSource("ids", ids),
                (RowCallbackHandler) result -> {
                    UUID testId = uuid(result, "test_evidence_id");
                    int index = result.getInt("criterion_index");
                    String criterion = result.getString("criterion");
                    String status = result.getString("status");
                    String summary = result.getString("summary");
                    AcceptanceBuilder builder = acceptance
                            .computeIfAbsent(testId, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(index, ignored -> new AcceptanceBuilder(
                                    index, criterion, status, summary));
                    UUID commandId = nullableUuid(result, "command_evidence_id");
                    if (commandId != null) {
                        builder.commandIds.add(commandId);
                    }
                });
        rows.forEach(row -> {
            row.commandIds = List.copyOf(commands.getOrDefault(row.id, List.of()));
            row.acceptance = acceptance.getOrDefault(row.id, Map.of()).values().stream()
                    .map(AcceptanceBuilder::projection)
                    .toList();
        });
    }

    private static AttemptRow attemptRow(ResultSet result) throws SQLException {
        UUID workspaceId = uuid(result, "ew_id");
        Optional<UUID> diffId = Optional.ofNullable(nullableUuid(result, "diff_id"));
        WorkspaceSummary workspace = new WorkspaceSummary(
                workspaceId,
                result.getString("repository_key"),
                result.getString("baseline_commit"),
                result.getString("managed_branch"),
                result.getString("ew_status"),
                result.getLong("recovery_generation"),
                result.getString("completion_reason"),
                result.getString("failure_code"),
                result.getString("workspace_fingerprint"),
                result.getLong("ew_version"),
                instant(result, "retain_until"),
                instant(result, "ew_created_at"),
                instant(result, "ew_updated_at"));
        Optional<SandboxSummary> sandbox = nullableUuid(result, "policy_id") == null
                ? Optional.empty()
                : Optional.of(new SandboxSummary(
                        result.getString("sandbox_network_mode"),
                        result.getInt("sandbox_cpu_count"),
                        result.getInt("sandbox_memory_mib"),
                        result.getInt("sandbox_pids"),
                        result.getInt("sandbox_max_command_duration_seconds"),
                        result.getLong("sandbox_max_command_output_bytes"),
                        result.getBoolean("sandbox_read_only_root_filesystem"),
                        result.getInt("max_command_calls"),
                        result.getInt("max_changed_files"),
                        result.getLong("max_single_file_bytes"),
                        result.getInt("max_write_operations"),
                        result.getLong("max_written_bytes"),
                        result.getLong("max_diff_bytes"),
                        result.getInt("max_test_repair_rounds"),
                        result.getString("build_profile_key"),
                        result.getLong("build_profile_version")));
        return new AttemptRow(
                new TaskExecutionId(uuid(result, "task_execution_id")),
                result.getInt("attempt"),
                workspace,
                sandbox,
                diffId,
                nullableUuid(result, "result_test_id"),
                result.getLong("command_count"),
                result.getLong("test_count"),
                result);
    }

    private static TestRow testRow(ResultSet result) throws SQLException {
        UUID reportId = nullableUuid(result, "test_report_artifact_id");
        Optional<ArtifactSummary> report = reportId == null
                ? Optional.empty()
                : Optional.of(new ArtifactSummary(
                        reportId,
                        result.getString("test_report_kind"),
                        result.getString("test_report_content_type"),
                        result.getLong("test_report_size_bytes"),
                        result.getString("test_report_content_hash")));
        return new TestRow(
                uuid(result, "id"),
                result.getLong("evidence_sequence"),
                result.getLong("diff_generation"),
                result.getString("diff_manifest_hash"),
                result.getLong("test_total"),
                result.getLong("test_passed"),
                result.getLong("test_failed"),
                result.getLong("test_errors"),
                result.getLong("test_skipped"),
                result.getString("summary"),
                result.getString("failure_classification"),
                result.getString("evidence_hash"),
                report,
                instant(result, "created_at"));
    }

    private static MapSqlParameterSource scope(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId) {
        return new MapSqlParameterSource()
                .addValue("organizationId", Objects.requireNonNull(organizationId).value())
                .addValue("teamId", Objects.requireNonNull(teamId).value())
                .addValue("projectId", Objects.requireNonNull(projectId).value())
                .addValue("taskId", Objects.requireNonNull(taskId).value());
    }

    private static MapSqlParameterSource evidenceScope(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<CodingEvidenceCursor> cursor,
            int limit) {
        Optional<CodingEvidenceCursor> position = Objects.requireNonNull(cursor, "cursor");
        return scope(organizationId, teamId, projectId, taskId)
                .addValue("executionId", Objects.requireNonNull(executionId).value())
                .addValue("cursorSequence", position.map(CodingEvidenceCursor::sequence).orElse(null))
                .addValue("cursorId", position.map(CodingEvidenceCursor::id).orElse(null))
                .addValue("fetchLimit", limit + 1);
    }

    private static <T> CodingEvidencePage<T> page(
            List<T> values,
            int limit,
            java.util.function.Function<T, CodingEvidenceCursor> cursor) {
        boolean hasMore = values.size() > limit;
        List<T> items = hasMore ? List.copyOf(values.subList(0, limit)) : List.copyOf(values);
        return new CodingEvidencePage<>(
                items,
                hasMore ? Optional.of(cursor.apply(items.get(items.size() - 1))) : Optional.empty());
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private static UUID uuid(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    private static UUID nullableUuid(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static final class AttemptRow {
        private final TaskExecutionId executionId;
        private final int attempt;
        private final WorkspaceSummary workspace;
        private final Optional<SandboxSummary> sandbox;
        private final Optional<UUID> diffArtifactId;
        private final Optional<DiffManifestSummary> diff;
        private final Optional<CodingResultSummary> result;
        private final long commandCount;
        private final long testCount;

        private AttemptRow(
                TaskExecutionId executionId,
                int attempt,
                WorkspaceSummary workspace,
                Optional<SandboxSummary> sandbox,
                Optional<UUID> diffArtifactId,
                UUID resultTestId,
                long commandCount,
                long testCount,
                ResultSet row) throws SQLException {
            this.executionId = executionId;
            this.attempt = attempt;
            this.workspace = workspace;
            this.sandbox = sandbox;
            this.diffArtifactId = diffArtifactId;
            this.commandCount = commandCount;
            this.testCount = testCount;
            if (diffArtifactId.isPresent()) {
                this.diff = Optional.of(new DiffManifestSummary(
                        diffArtifactId.orElseThrow(),
                        row.getLong("diff_generation"),
                        row.getString("manifest_hash"),
                        row.getInt("file_count"),
                        row.getLong("additions"),
                        row.getLong("deletions"),
                        row.getString("diff_baseline_commit"),
                        row.getString("delivery_commit"),
                        row.getString("diff_final_hash"),
                        new ArtifactSummary(
                                uuid(row, "patch_artifact_id"),
                                row.getString("patch_artifact_kind"),
                                row.getString("patch_content_type"),
                                row.getLong("patch_size_bytes"),
                                row.getString("patch_sha256")),
                        List.of(),
                        instant(row, "diff_created_at")));
            } else {
                this.diff = Optional.empty();
            }
            this.result = resultTestId == null || diffArtifactId.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new CodingResultSummary(
                            "1",
                            workspace.id(),
                            workspace.fingerprint(),
                            uuid(row, "coding_target_snapshot_id"),
                            row.getLong("coding_target_revision"),
                            row.getString("coding_target_hash"),
                            diffArtifactId.orElseThrow(),
                            row.getString("diff_final_hash"),
                            resultTestId,
                            row.getString("result_test_hash"),
                            instant(row, "result_completed_at")));
        }

        private CodingAttemptProjection projection(Map<UUID, List<DiffFileSummary>> files) {
            Optional<DiffManifestSummary> manifest = diff.map(value -> new DiffManifestSummary(
                    value.artifactId(), value.generation(), value.manifestHash(), value.fileCount(),
                    value.additions(), value.deletions(), value.baselineCommit(), value.deliveryCommit(),
                    value.finalHash(), value.patch(),
                    files.getOrDefault(value.artifactId(), List.of()), value.createdAt()));
            return new CodingAttemptProjection(
                    executionId, attempt, workspace, sandbox, manifest, result, commandCount, testCount);
        }
    }

    private static final class TestRow {
        private final UUID id;
        private final long sequence;
        private final long generation;
        private final String manifestHash;
        private final long total;
        private final long passed;
        private final long failed;
        private final long errors;
        private final long skipped;
        private final String summary;
        private final String failure;
        private final String hash;
        private final Optional<ArtifactSummary> report;
        private final Instant createdAt;
        private List<UUID> commandIds = List.of();
        private List<AcceptanceProjection> acceptance = List.of();

        private TestRow(UUID id, long sequence, long generation, String manifestHash,
                long total, long passed, long failed, long errors, long skipped,
                String summary, String failure, String hash,
                Optional<ArtifactSummary> report, Instant createdAt) {
            this.id = id;
            this.sequence = sequence;
            this.generation = generation;
            this.manifestHash = manifestHash;
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.errors = errors;
            this.skipped = skipped;
            this.summary = summary;
            this.failure = failure;
            this.hash = hash;
            this.report = report;
            this.createdAt = createdAt;
        }

        private TestEvidenceProjection projection() {
            return new TestEvidenceProjection(id, sequence, generation, manifestHash,
                    total, passed, failed, errors, skipped, summary, failure, hash,
                    commandIds, acceptance, report, createdAt);
        }
    }

    private static final class AcceptanceBuilder {
        private final int index;
        private final String criterion;
        private final String status;
        private final String summary;
        private final List<UUID> commandIds = new ArrayList<>();

        private AcceptanceBuilder(int index, String criterion, String status, String summary) {
            this.index = index;
            this.criterion = criterion;
            this.status = status;
            this.summary = summary;
        }

        private AcceptanceProjection projection() {
            return new AcceptanceProjection(index, criterion, status, summary, commandIds);
        }
    }
}
