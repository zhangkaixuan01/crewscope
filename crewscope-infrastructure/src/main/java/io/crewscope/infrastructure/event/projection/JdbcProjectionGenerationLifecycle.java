package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionCanonicalHash;
import io.crewscope.domain.projection.ProjectionFailedPartition;
import io.crewscope.domain.projection.ProjectionFencingToken;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionSnapshot;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists canonical validation and performs ADR-020's fixed-lock-order atomic switch. */
@Repository
public class JdbcProjectionGenerationLifecycle {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final TransactionTemplate transaction;

    @Autowired
    public JdbcProjectionGenerationLifecycle(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, transactionManager, Clock.systemUTC());
    }

    JdbcProjectionGenerationLifecycle(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setName("crewscope-projection-generation-lifecycle");
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ProjectionValidationOutcome validate(
            GenerationAwareProjectionHandler handler, ProjectionValidationRequest request) {
        GenerationAwareProjectionHandler projector = Objects.requireNonNull(handler, "handler");
        ProjectionValidationRequest command = Objects.requireNonNull(request, "request");
        ProjectionValidationOutcome outcome = transaction.execute(status ->
                validateInTransaction(projector, command));
        return Objects.requireNonNull(outcome, "validation outcome");
    }

    public ProjectionSwitchOutcome switchGeneration(
            GenerationAwareProjectionHandler handler, ProjectionSwitchRequest request) {
        GenerationAwareProjectionHandler projector = Objects.requireNonNull(handler, "handler");
        ProjectionSwitchRequest command = Objects.requireNonNull(request, "request");
        ProjectionSwitchOutcome outcome = transaction.execute(status ->
                switchInTransaction(projector, command));
        return Objects.requireNonNull(outcome, "switch outcome");
    }

    private ProjectionValidationOutcome validateInTransaction(
            GenerationAwareProjectionHandler handler, ProjectionValidationRequest request) {
        requireHandler(request.generationKey().projectionName().value(), handler);
        GenerationRow generation = lockGeneration(
                request.generationKey().organizationId().value(),
                request.generationKey().projectionName().value(),
                request.generationKey().generation().value());
        JobRow job = lockJob(
                request.generationKey().organizationId().value(), request.rebuildJobId().value());
        requireValidationCoordinates(request, generation, job);

        ProjectionSnapshot expected = handler.expectedSnapshot(
                request.generationKey().organizationId());
        ProjectionSnapshot actual = handler.actualSnapshot(request.generationKey());
        boolean passed = expected.rowCount() == actual.rowCount()
                && expected.canonicalHash().equals(actual.canonicalHash())
                && expected.healthy()
                && actual.healthy();
        if (!passed && generation.status().equals("VALIDATING")) {
            throw new IllegalStateException(
                    "A VALIDATING Generation cannot replace its successful snapshot with a failure");
        }

        UUID validationId = UUID.randomUUID();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.projection_validation_result (
                    id, organization_id, projection_name, generation, rebuild_job_id,
                    definition_version, expected_row_count, expected_canonical_hash,
                    expected_gap_count, actual_row_count, actual_canonical_hash,
                    actual_gap_count, passed, validated_by_principal_id, validated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                validationId,
                request.generationKey().organizationId().value(),
                request.generationKey().projectionName().value(),
                request.generationKey().generation().value(),
                request.rebuildJobId().value(),
                request.expectedDefinitionVersion().value(),
                expected.rowCount(),
                expected.canonicalHash().value(),
                expected.gapCount(),
                actual.rowCount(),
                actual.canonicalHash().value(),
                actual.gapCount(),
                passed,
                request.actorId().value(),
                now);
        saveFailedPartitions(validationId, "EXPECTED", expected.failedPartitions());
        saveFailedPartitions(validationId, "ACTUAL", actual.failedPartitions());

        String nextStatus = passed ? "VALIDATING" : generation.status();
        updateGenerationValidation(request, validationId, nextStatus, now);
        updateJobValidation(request, validationId, passed ? "VALIDATING" : job.status(), now);
        ProjectionGenerationLease lease = new ProjectionGenerationLease(
                request.generationKey(), new ProjectionFencingToken(generation.fencingToken() + 1));
        return new ProjectionValidationOutcome(
                validationId,
                passed,
                expected,
                actual,
                lease,
                generation.version() + 1,
                job.version() + 1);
    }

    private ProjectionSwitchOutcome switchInTransaction(
            GenerationAwareProjectionHandler handler, ProjectionSwitchRequest request) {
        requireHandler(request.targetGeneration().projectionName().value(), handler);
        UUID organizationId = request.targetGeneration().organizationId().value();
        String projectionName = request.targetGeneration().projectionName().value();
        PointerRow pointer = lockPointer(organizationId, projectionName);
        GenerationRow target = lockGeneration(
                organizationId, projectionName, request.targetGeneration().generation().value());
        GenerationRow previous = lockGeneration(
                organizationId, projectionName, request.previousActiveGeneration().value());
        JobRow job = lockJob(organizationId, request.rebuildJobId().value());
        requireSwitchCoordinates(request, pointer, previous, target, job);

        ProjectionSnapshot validated = loadSuccessfulActualSnapshot(
                target.currentValidationId());
        ProjectionSnapshot current = handler.actualSnapshot(request.targetGeneration());
        if (!validated.equals(current)) {
            throw new IllegalStateException(
                    "Projection validation is stale; the target Generation must be revalidated");
        }

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        updateGenerationStatus(
                organizationId,
                projectionName,
                previous.generation(),
                "RETIRED",
                request.expectedPreviousGenerationVersion(),
                now);
        updateGenerationStatus(
                organizationId,
                projectionName,
                target.generation(),
                "ACTIVE",
                request.expectedTargetGenerationVersion(),
                now);
        int pointerUpdated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_pointer
                SET active_generation = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND version = ?
                """,
                target.generation(),
                now,
                organizationId,
                projectionName,
                request.expectedPointerVersion());
        if (pointerUpdated != 1) {
            throw new IllegalStateException("Projection Pointer changed while locked");
        }
        int jobUpdated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_rebuild_job
                SET status = 'COMPLETED', version = version + 1, updated_at = ?
                WHERE organization_id = ?
                  AND id = ?
                  AND version = ?
                  AND status = 'VALIDATING'
                """,
                now,
                organizationId,
                request.rebuildJobId().value(),
                request.expectedJobVersion());
        if (jobUpdated != 1) {
            throw new IllegalStateException("Projection RebuildJob changed while locked");
        }
        return new ProjectionSwitchOutcome(
                new ProjectionGenerationLease(
                        request.targetGeneration(),
                        new ProjectionFencingToken(target.fencingToken() + 1)),
                pointer.version() + 1,
                previous.version() + 1,
                target.version() + 1,
                job.version() + 1);
    }

    private void requireValidationCoordinates(
            ProjectionValidationRequest request, GenerationRow generation, JobRow job) {
        if (!generation.status().equals("BUILDING") && !generation.status().equals("VALIDATING")) {
            throw new IllegalStateException("Only a writable shadow Generation can be validated");
        }
        if (!job.status().equals("BUILDING") && !job.status().equals("VALIDATING")) {
            throw new IllegalStateException("Only a live RebuildJob can be validated");
        }
        if (generation.version() != request.expectedGenerationVersion()
                || job.version() != request.expectedJobVersion()
                || generation.definitionVersion() != request.expectedDefinitionVersion().value()
                || job.definitionVersion() != request.expectedDefinitionVersion().value()
                || !request.rebuildJobId().value().equals(generation.rebuildJobId())
                || generation.generation() != job.generation()) {
            throw new IllegalStateException("Projection validation coordinates or versions changed");
        }
    }

    private void requireSwitchCoordinates(
            ProjectionSwitchRequest request,
            PointerRow pointer,
            GenerationRow previous,
            GenerationRow target,
            JobRow job) {
        if (pointer.activeGeneration() != request.previousActiveGeneration().value()
                || pointer.version() != request.expectedPointerVersion()
                || previous.generation() != pointer.activeGeneration()
                || !previous.status().equals("ACTIVE")
                || previous.version() != request.expectedPreviousGenerationVersion()
                || !target.status().equals("VALIDATING")
                || target.version() != request.expectedTargetGenerationVersion()
                || !job.status().equals("VALIDATING")
                || job.version() != request.expectedJobVersion()
                || target.definitionVersion() != request.expectedDefinitionVersion().value()
                || job.definitionVersion() != request.expectedDefinitionVersion().value()
                || !request.rebuildJobId().value().equals(target.rebuildJobId())
                || target.generation() != job.generation()
                || target.currentValidationId() == null
                || !target.currentValidationId().equals(job.currentValidationId())) {
            throw new IllegalStateException("Projection switch coordinates or versions changed");
        }
    }

    private void updateGenerationValidation(
            ProjectionValidationRequest request,
            UUID validationId,
            String status,
            OffsetDateTime now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_generation
                SET status = ?, current_validation_id = ?,
                    fencing_token = fencing_token + 1,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND generation = ?
                  AND version = ?
                """,
                status,
                validationId,
                now,
                request.generationKey().organizationId().value(),
                request.generationKey().projectionName().value(),
                request.generationKey().generation().value(),
                request.expectedGenerationVersion());
        if (updated != 1) {
            throw new IllegalStateException("Projection Generation changed while locked");
        }
    }

    private void updateJobValidation(
            ProjectionValidationRequest request,
            UUID validationId,
            String status,
            OffsetDateTime now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_rebuild_job
                SET status = ?, current_validation_id = ?,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ?
                  AND id = ?
                  AND version = ?
                """,
                status,
                validationId,
                now,
                request.generationKey().organizationId().value(),
                request.rebuildJobId().value(),
                request.expectedJobVersion());
        if (updated != 1) {
            throw new IllegalStateException("Projection RebuildJob changed while locked");
        }
    }

    private void updateGenerationStatus(
            UUID organizationId,
            String projectionName,
            long generation,
            String status,
            long expectedVersion,
            OffsetDateTime now) {
        int updated = jdbcTemplate.update(
                """
                UPDATE crewscope.projection_generation
                SET status = ?, fencing_token = fencing_token + 1,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ?
                  AND projection_name = ?
                  AND generation = ?
                  AND version = ?
                """,
                status,
                now,
                organizationId,
                projectionName,
                generation,
                expectedVersion);
        if (updated != 1) {
            throw new IllegalStateException("Projection Generation changed while locked");
        }
    }

    private GenerationRow lockGeneration(UUID organizationId, String projectionName, long generation) {
        GenerationRow row = jdbcTemplate.query(
                """
                SELECT generation, definition_version, rebuild_job_id, status,
                       fencing_token, current_validation_id, version
                FROM crewscope.projection_generation
                WHERE organization_id = ? AND projection_name = ? AND generation = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new GenerationRow(
                                resultSet.getLong("generation"),
                                resultSet.getLong("definition_version"),
                                resultSet.getObject("rebuild_job_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getLong("fencing_token"),
                                resultSet.getObject("current_validation_id", UUID.class),
                                resultSet.getLong("version"))
                        : null,
                organizationId,
                projectionName,
                generation);
        return Objects.requireNonNull(row, "Projection Generation was not found");
    }

    private PointerRow lockPointer(UUID organizationId, String projectionName) {
        PointerRow row = jdbcTemplate.query(
                """
                SELECT active_generation, version
                FROM crewscope.projection_pointer
                WHERE organization_id = ? AND projection_name = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new PointerRow(
                                resultSet.getLong("active_generation"),
                                resultSet.getLong("version"))
                        : null,
                organizationId,
                projectionName);
        return Objects.requireNonNull(row, "Projection Pointer was not found");
    }

    private JobRow lockJob(UUID organizationId, UUID jobId) {
        JobRow row = jdbcTemplate.query(
                """
                SELECT generation, definition_version, status, current_validation_id, version
                FROM crewscope.projection_rebuild_job
                WHERE organization_id = ? AND id = ?
                FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? new JobRow(
                                resultSet.getLong("generation"),
                                resultSet.getLong("definition_version"),
                                resultSet.getString("status"),
                                resultSet.getObject("current_validation_id", UUID.class),
                                resultSet.getLong("version"))
                        : null,
                organizationId,
                jobId);
        return Objects.requireNonNull(row, "Projection RebuildJob was not found");
    }

    private ProjectionSnapshot loadSuccessfulActualSnapshot(UUID validationId) {
        ProjectionSnapshot snapshot = jdbcTemplate.query(
                """
                SELECT actual_row_count, actual_canonical_hash, actual_gap_count, passed
                FROM crewscope.projection_validation_result
                WHERE id = ?
                """,
                resultSet -> {
                    if (!resultSet.next() || !resultSet.getBoolean("passed")) {
                        return null;
                    }
                    return new ProjectionSnapshot(
                            resultSet.getLong("actual_row_count"),
                            new ProjectionCanonicalHash(
                                    resultSet.getString("actual_canonical_hash")),
                            resultSet.getLong("actual_gap_count"),
                            List.of());
                },
                validationId);
        return Objects.requireNonNull(snapshot, "Successful Projection validation was not found");
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
                    validationId,
                    side,
                    partition.partitionHash().value(),
                    partition.failureCode().value());
        }
    }

    private static void requireHandler(
            String projectionName, GenerationAwareProjectionHandler handler) {
        if (!handler.definition().name().value().equals(projectionName)) {
            throw new IllegalArgumentException("Projection handler belongs to another Definition");
        }
    }

    private record GenerationRow(
            long generation,
            long definitionVersion,
            UUID rebuildJobId,
            String status,
            long fencingToken,
            UUID currentValidationId,
            long version) {}

    private record JobRow(
            long generation,
            long definitionVersion,
            String status,
            UUID currentValidationId,
            long version) {}

    private record PointerRow(long activeGeneration, long version) {}
}
