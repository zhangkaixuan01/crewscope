package io.crewscope.infrastructure.persistence.projection;

import io.crewscope.application.projection.ProjectionAdministrationCommandId;
import io.crewscope.application.projection.ProjectionAdministrationRepository;
import io.crewscope.application.projection.ProjectionAdministrationResult;
import io.crewscope.application.projection.ProjectionCommandFingerprint;
import io.crewscope.application.projection.ProjectionCommandReceipt;
import io.crewscope.application.projection.ProjectionRegistrySnapshot;
import io.crewscope.domain.projection.*;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.infrastructure.persistence.operations.AtomicOperationsEventWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the fixed-lock-order projection administration Port. */
@Repository
public class JdbcProjectionAdministrationRepositoryAdapter
        implements ProjectionAdministrationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicOperationsEventWriter eventWriter;

    public JdbcProjectionAdministrationRepositoryAdapter(
            JdbcTemplate jdbcTemplate, AtomicOperationsEventWriter eventWriter) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.eventWriter = java.util.Objects.requireNonNull(eventWriter, "eventWriter");
    }

    @Override
    public Optional<ProjectionCommandReceipt> findReceipt(
            OrganizationId organizationId, ProjectionAdministrationCommandId commandId) {
        List<ProjectionCommandReceipt> rows = jdbcTemplate.query(
                """
                SELECT request_fingerprint, projection_name, generation, rebuild_job_id,
                       generation_status, rebuild_status, pointer_version
                FROM crewscope.projection_command_receipt
                WHERE organization_id = ? AND command_id = ?
                """,
                (rs, row) -> receipt(organizationId, commandId, rs),
                organizationId.value(), commandId.value());
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionRegistrySnapshot loadForUpdate(
            OrganizationId organizationId, ProjectionName projectionName) {
        lockPointer(organizationId, projectionName);
        jdbcTemplate.queryForList(
                """
                SELECT generation FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ?
                ORDER BY generation FOR UPDATE
                """,
                organizationId.value(), projectionName.value());
        jdbcTemplate.queryForList(
                """
                SELECT id FROM crewscope.projection_rebuild_job
                WHERE organization_id = ? AND projection_name = ?
                ORDER BY generation FOR UPDATE
                """,
                organizationId.value(), projectionName.value());
        return snapshot(organizationId, projectionName);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionRegistrySnapshot loadForSwitch(
            OrganizationId organizationId,
            ProjectionName projectionName,
            ProjectionGeneration targetGeneration) {
        long active = lockPointer(organizationId, projectionName);
        lockGeneration(organizationId, projectionName, targetGeneration.value());
        lockGeneration(organizationId, projectionName, active);
        jdbcTemplate.queryForList(
                """
                SELECT id FROM crewscope.projection_rebuild_job
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                FOR UPDATE
                """,
                organizationId.value(), projectionName.value(), targetGeneration.value());
        return snapshot(organizationId, projectionName);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionCommandReceipt createRebuild(
            ProjectionRebuildStart start,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt) {
        ProjectionGenerationState generation = start.generation();
        ProjectionRebuildJob job = start.job();
        requireReceipt(receipt, generation, job);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_generation (
                    organization_id, projection_name, generation, definition_version,
                    rebuild_job_id, status, fencing_token, current_validation_id,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                """,
                generation.key().organizationId().value(),
                generation.key().projectionName().value(),
                generation.key().generation().value(),
                generation.definitionVersion().value(),
                generation.rebuildJobId().orElseThrow().value(),
                generation.status().name(),
                generation.fencingToken().value(),
                generation.version(),
                generation.createdAt().toOffsetDateTime(),
                generation.updatedAt().toOffsetDateTime());
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_rebuild_job (
                    id, organization_id, projection_name, definition_version, generation,
                    retry_of, requested_by_principal_id, status, current_validation_id,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                """,
                job.id().value(), job.organizationId().value(), job.projectionName().value(),
                job.definitionVersion().value(), job.generation().value(),
                job.retryOf().map(ProjectionRebuildJobId::value).orElse(null),
                job.requestedBy().value(), job.status().name(), job.version(),
                job.createdAt().toOffsetDateTime(), job.updatedAt().toOffsetDateTime());
        return finish(event, receipt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionCommandReceipt saveValidation(
            ProjectionValidationPlan plan,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt) {
        ProjectionValidationResult validation = plan.result();
        UUID validationId = UUID.randomUUID();
        saveValidation(validationId, plan.generation().key().organizationId(),
                plan.generation().key().projectionName(), validation);
        updateGeneration(plan.generation(), validationId);
        updateJob(plan.job(), validationId);
        return finish(event, receipt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionCommandReceipt switchGeneration(
            ProjectionSwitchPlan plan,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt) {
        ProjectionPointer pointer = plan.pointer();
        UUID targetValidation = currentValidationIdFromDatabase(plan.activatedTarget());
        UUID previousValidation = currentValidationIdFromDatabase(plan.retiredPrevious());
        updateGeneration(plan.retiredPrevious(), previousValidation);
        // The partial unique index permits only one ACTIVE row, so retirement must be persisted
        // before activation while all rows remain protected by the fixed lock order.
        updateGeneration(plan.activatedTarget(), targetValidation);
        int pointerUpdated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_pointer
                SET active_generation = ?, version = ?, updated_at = ?
                WHERE organization_id = ? AND projection_name = ? AND version = ?
                """,
                pointer.activeGeneration().value(), pointer.version(),
                pointer.updatedAt().toOffsetDateTime(), pointer.organizationId().value(),
                pointer.projectionName().value(), pointer.version() - 1);
        requireOne(pointerUpdated, "Projection Pointer");
        updateJob(plan.completedJob(), targetValidation);
        return finish(event, receipt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionCommandReceipt terminateRebuild(
            ProjectionTerminationPlan plan,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt) {
        UUID validationId = currentValidationIdFromDatabase(plan.generation());
        updateGeneration(plan.generation(), validationId);
        updateJob(plan.job(), validationId);
        return finish(event, receipt);
    }

    private ProjectionCommandReceipt finish(
            ProjectionLifecycleEvent event, ProjectionCommandReceipt receipt) {
        ProjectionAdministrationResult result = receipt.result();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_command_receipt (
                    organization_id, command_id, request_fingerprint, projection_name,
                    generation, rebuild_job_id, generation_status, rebuild_status,
                    pointer_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receipt.organizationId().value(), receipt.commandId().value(),
                receipt.fingerprint().value(), result.projectionName().value(),
                result.generation().value(), result.rebuildJobId().value(),
                result.generationStatus().name(), result.rebuildStatus().name(),
                result.pointerVersion().isPresent() ? result.pointerVersion().getAsLong() : null,
                event.occurredAt().toOffsetDateTime());
        eventWriter.append(
                eventType(event.eventType()),
                "PROJECTION_COMMAND",
                event.commandId(),
                event.organizationId(),
                event.actorId(),
                event.occurredAt(),
                event);
        return receipt;
    }

    private void updateGeneration(ProjectionGenerationState state, UUID validationId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_generation
                SET status = ?, fencing_token = ?, current_validation_id = ?,
                    version = ?, updated_at = ?
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                  AND version = ?
                """,
                state.status().name(), state.fencingToken().value(), validationId,
                state.version(), state.updatedAt().toOffsetDateTime(),
                state.key().organizationId().value(), state.key().projectionName().value(),
                state.key().generation().value(), state.version() - 1);
        requireOne(updated, "Projection Generation");
    }

    private void updateJob(ProjectionRebuildJob job, UUID validationId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_rebuild_job
                SET status = ?, current_validation_id = ?, version = ?, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """,
                job.status().name(), validationId, job.version(),
                job.updatedAt().toOffsetDateTime(), job.organizationId().value(),
                job.id().value(), job.version() - 1);
        requireOne(updated, "Projection RebuildJob");
    }

    private void saveValidation(
            UUID id,
            OrganizationId organizationId,
            ProjectionName projectionName,
            ProjectionValidationResult result) {
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_validation_result (
                    id, organization_id, projection_name, generation, rebuild_job_id,
                    definition_version, expected_row_count, expected_canonical_hash,
                    expected_gap_count, actual_row_count, actual_canonical_hash,
                    actual_gap_count, passed, validated_by_principal_id, validated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, organizationId.value(), projectionName.value(), result.generation().value(),
                result.rebuildJobId().value(), result.definitionVersion().value(),
                result.expected().rowCount(), result.expected().canonicalHash().value(),
                result.expected().gapCount(), result.actual().rowCount(),
                result.actual().canonicalHash().value(), result.actual().gapCount(),
                result.passed(), result.validatedBy().value(), result.validatedAt().toOffsetDateTime());
        saveFailedPartitions(id, "EXPECTED", result.expected().failedPartitions());
        saveFailedPartitions(id, "ACTUAL", result.actual().failedPartitions());
    }

    private void saveFailedPartitions(
            UUID validationId, String side, List<ProjectionFailedPartition> partitions) {
        for (ProjectionFailedPartition partition : partitions) {
            jdbcTemplate.update(
                    """
                    INSERT INTO crewscope.projection_validation_failed_partition (
                        validation_id, snapshot_side, partition_hash, failure_code
                    ) VALUES (?, ?, ?, ?)
                    """,
                    validationId, side, partition.partitionHash().value(),
                    partition.failureCode().value());
        }
    }

    private ProjectionRegistrySnapshot snapshot(
            OrganizationId organizationId, ProjectionName projectionName) {
        ProjectionPointer pointer = jdbcTemplate.queryForObject(
                """
                SELECT active_generation, version, updated_at
                FROM crewscope.projection_pointer
                WHERE organization_id = ? AND projection_name = ?
                """,
                (rs, row) -> new ProjectionPointer(
                        organizationId, projectionName,
                        new ProjectionGeneration(rs.getLong("active_generation")),
                        timestamp(rs, "updated_at"), rs.getLong("version")),
                organizationId.value(), projectionName.value());
        ProjectionDefinition definition = jdbcTemplate.queryForObject(
                """
                SELECT definition.definition_version, definition.projection_schema_version,
                       definition.canonical_encoder, definition.validator
                FROM crewscope.projection_pointer pointer
                JOIN crewscope.projection_generation generation
                  ON generation.organization_id = pointer.organization_id
                 AND generation.projection_name = pointer.projection_name
                 AND generation.generation = pointer.active_generation
                JOIN crewscope.projection_definition definition
                  ON definition.projection_name = generation.projection_name
                 AND definition.definition_version = generation.definition_version
                WHERE pointer.organization_id = ? AND pointer.projection_name = ?
                """,
                (rs, row) -> new ProjectionDefinition(
                        projectionName,
                        new ProjectionDefinitionVersion(rs.getLong("definition_version")),
                        new SchemaVersion(rs.getInt("projection_schema_version")),
                        rs.getString("canonical_encoder"), rs.getString("validator")),
                organizationId.value(), projectionName.value());
        List<ProjectionGenerationState> generations = jdbcTemplate.query(
                """
                SELECT generation, definition_version, rebuild_job_id, status, fencing_token,
                       current_validation_id, version, created_at, updated_at
                FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ? ORDER BY generation
                """,
                (rs, row) -> ProjectionGenerationState.reconstitute(
                        new ProjectionGenerationKey(organizationId, projectionName,
                                new ProjectionGeneration(rs.getLong("generation"))),
                        new ProjectionDefinitionVersion(rs.getLong("definition_version")),
                        Optional.ofNullable(rs.getObject("rebuild_job_id", UUID.class))
                                .map(ProjectionRebuildJobId::new),
                        ProjectionGenerationStatus.valueOf(rs.getString("status")),
                        new ProjectionFencingToken(rs.getLong("fencing_token")),
                        validation(rs.getObject("current_validation_id", UUID.class)),
                        timestamp(rs, "created_at"), timestamp(rs, "updated_at"),
                        rs.getLong("version")),
                organizationId.value(), projectionName.value());
        List<ProjectionRebuildJob> jobs = jdbcTemplate.query(
                """
                SELECT id, definition_version, generation, retry_of,
                       requested_by_principal_id, status, current_validation_id,
                       version, created_at, updated_at
                FROM crewscope.projection_rebuild_job
                WHERE organization_id = ? AND projection_name = ? ORDER BY generation
                """,
                (rs, row) -> ProjectionRebuildJob.reconstitute(
                        new ProjectionRebuildJobId(rs.getObject("id", UUID.class)),
                        organizationId, projectionName,
                        new ProjectionDefinitionVersion(rs.getLong("definition_version")),
                        new ProjectionGeneration(rs.getLong("generation")),
                        Optional.ofNullable(rs.getObject("retry_of", UUID.class))
                                .map(ProjectionRebuildJobId::new),
                        new PrincipalId(rs.getObject("requested_by_principal_id", UUID.class)),
                        ProjectionRebuildStatus.valueOf(rs.getString("status")),
                        validation(rs.getObject("current_validation_id", UUID.class)),
                        timestamp(rs, "created_at"), timestamp(rs, "updated_at"),
                        rs.getLong("version")),
                organizationId.value(), projectionName.value());
        return new ProjectionRegistrySnapshot(definition, pointer, generations, jobs);
    }

    private Optional<ProjectionValidationResult> validation(UUID validationId) {
        if (validationId == null) {
            return Optional.empty();
        }
        ProjectionValidationResult result = jdbcTemplate.queryForObject(
                """
                SELECT definition_version, generation, rebuild_job_id,
                       expected_row_count, expected_canonical_hash, expected_gap_count,
                       actual_row_count, actual_canonical_hash, actual_gap_count,
                       validated_by_principal_id, validated_at
                FROM crewscope.projection_validation_result WHERE id = ?
                """,
                (rs, row) -> new ProjectionValidationResult(
                        new ProjectionDefinitionVersion(rs.getLong("definition_version")),
                        new ProjectionGeneration(rs.getLong("generation")),
                        new ProjectionRebuildJobId(rs.getObject("rebuild_job_id", UUID.class)),
                        snapshot(validationId, "EXPECTED", rs),
                        snapshot(validationId, "ACTUAL", rs),
                        new PrincipalId(rs.getObject("validated_by_principal_id", UUID.class)),
                        timestamp(rs, "validated_at")),
                validationId);
        return Optional.ofNullable(result);
    }

    private ProjectionSnapshot snapshot(UUID validationId, String side, ResultSet rs)
            throws SQLException {
        String prefix = side.equals("EXPECTED") ? "expected" : "actual";
        List<ProjectionFailedPartition> partitions = jdbcTemplate.query(
                """
                SELECT partition_hash, failure_code
                FROM crewscope.projection_validation_failed_partition
                WHERE validation_id = ? AND snapshot_side = ?
                ORDER BY partition_hash, failure_code
                """,
                (row, index) -> new ProjectionFailedPartition(
                        new ProjectionCanonicalHash(row.getString("partition_hash")),
                        new ProjectionFailureCode(row.getString("failure_code"))),
                validationId, side);
        return new ProjectionSnapshot(
                rs.getLong(prefix + "_row_count"),
                new ProjectionCanonicalHash(rs.getString(prefix + "_canonical_hash")),
                rs.getLong(prefix + "_gap_count"), partitions);
    }

    private long lockPointer(OrganizationId organizationId, ProjectionName projectionName) {
        Long active = jdbcTemplate.queryForObject(
                """
                SELECT active_generation FROM crewscope.projection_pointer
                WHERE organization_id = ? AND projection_name = ? FOR UPDATE
                """,
                Long.class, organizationId.value(), projectionName.value());
        if (active == null) {
            throw new IllegalArgumentException("Projection Pointer was not found");
        }
        return active;
    }

    private void lockGeneration(
            OrganizationId organizationId, ProjectionName projectionName, long generation) {
        List<Long> rows = jdbcTemplate.queryForList(
                """
                SELECT generation FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                FOR UPDATE
                """,
                Long.class, organizationId.value(), projectionName.value(), generation);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("Projection Generation was not found");
        }
    }

    private static ProjectionCommandReceipt receipt(
            OrganizationId organizationId,
            ProjectionAdministrationCommandId commandId,
            ResultSet rs) throws SQLException {
        long pointer = rs.getLong("pointer_version");
        OptionalLong pointerVersion = rs.wasNull() ? OptionalLong.empty() : OptionalLong.of(pointer);
        ProjectionAdministrationResult result = new ProjectionAdministrationResult(
                organizationId,
                new ProjectionName(rs.getString("projection_name")),
                new ProjectionGeneration(rs.getLong("generation")),
                new ProjectionRebuildJobId(rs.getObject("rebuild_job_id", UUID.class)),
                ProjectionGenerationStatus.valueOf(rs.getString("generation_status")),
                ProjectionRebuildStatus.valueOf(rs.getString("rebuild_status")),
                pointerVersion);
        return new ProjectionCommandReceipt(
                commandId, organizationId,
                new ProjectionCommandFingerprint(rs.getString("request_fingerprint")), result);
    }

    private static void requireReceipt(
            ProjectionCommandReceipt receipt,
            ProjectionGenerationState generation,
            ProjectionRebuildJob job) {
        if (!receipt.organizationId().equals(generation.key().organizationId())
                || !receipt.result().rebuildJobId().equals(job.id())) {
            throw new IllegalArgumentException("Projection command receipt has mixed scope");
        }
    }

    private UUID currentValidationIdFromDatabase(ProjectionGenerationState state) {
        return jdbcTemplate.queryForObject(
                """
                SELECT current_validation_id FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                """,
                UUID.class,
                state.key().organizationId().value(),
                state.key().projectionName().value(),
                state.key().generation().value());
    }

    private static UtcTimestamp timestamp(ResultSet rs, String column) throws SQLException {
        return UtcTimestamp.from(rs.getObject(column, OffsetDateTime.class));
    }

    private static String eventType(ProjectionLifecycleEventType type) {
        return switch (type) {
            case REBUILD_STARTED -> "PROJECTION_REBUILD_STARTED";
            case REBUILD_RETRIED -> "PROJECTION_REBUILD_RETRIED";
            case VALIDATION_PASSED -> "PROJECTION_VALIDATION_PASSED";
            case VALIDATION_FAILED -> "PROJECTION_VALIDATION_FAILED";
            case GENERATION_SWITCHED -> "PROJECTION_GENERATION_SWITCHED";
            case REBUILD_CANCELLED -> "PROJECTION_REBUILD_CANCELLED";
            case REBUILD_FAILED -> "PROJECTION_REBUILD_FAILED";
        };
    }

    private static void requireOne(int updated, String type) {
        if (updated != 1) {
            throw new IllegalStateException(type + " changed while locked");
        }
    }
}
