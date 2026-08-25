package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Optimistically versioned Generation state machine with per-transition fencing. */
public final class ProjectionGenerationState {

    private final ProjectionGenerationKey key;
    private final ProjectionDefinitionVersion definitionVersion;
    private final Optional<ProjectionRebuildJobId> rebuildJobId;
    private final ProjectionGenerationStatus status;
    private final ProjectionFencingToken fencingToken;
    private final Optional<ProjectionValidationResult> validation;
    private final UtcTimestamp createdAt;
    private final UtcTimestamp updatedAt;
    private final long version;

    private ProjectionGenerationState(
            ProjectionGenerationKey key,
            ProjectionDefinitionVersion definitionVersion,
            Optional<ProjectionRebuildJobId> rebuildJobId,
            ProjectionGenerationStatus status,
            ProjectionFencingToken fencingToken,
            Optional<ProjectionValidationResult> validation,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            long version) {
        this.key = Objects.requireNonNull(key, "key");
        this.definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion");
        this.rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        this.status = Objects.requireNonNull(status, "status");
        this.fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || updatedAt.compareTo(createdAt) < 0) {
            throw new IllegalArgumentException("Projection Generation version or timestamps are invalid");
        }
        this.version = version;
        validateShape();
    }

    /** Creates an initial online generation; bootstrapping the Pointer is one infrastructure transaction. */
    public static ProjectionGenerationState active(
            OrganizationId organizationId,
            ProjectionDefinition definition,
            ProjectionGeneration generation,
            UtcTimestamp createdAt) {
        ProjectionDefinition required = Objects.requireNonNull(definition, "definition");
        return new ProjectionGenerationState(
                new ProjectionGenerationKey(organizationId, required.name(), generation),
                required.version(),
                Optional.empty(),
                ProjectionGenerationStatus.ACTIVE,
                ProjectionFencingToken.INITIAL,
                Optional.empty(),
                createdAt,
                createdAt,
                0);
    }

    /** Creates a registered shadow generation before historical replay begins. */
    public static ProjectionGenerationState building(
            OrganizationId organizationId,
            ProjectionDefinition definition,
            ProjectionGeneration generation,
            ProjectionRebuildJobId rebuildJobId,
            UtcTimestamp createdAt) {
        ProjectionDefinition required = Objects.requireNonNull(definition, "definition");
        return new ProjectionGenerationState(
                new ProjectionGenerationKey(organizationId, required.name(), generation),
                required.version(),
                Optional.of(Objects.requireNonNull(rebuildJobId, "rebuildJobId")),
                ProjectionGenerationStatus.BUILDING,
                ProjectionFencingToken.INITIAL,
                Optional.empty(),
                createdAt,
                createdAt,
                0);
    }

    /** Reconstitutes persisted state without performing a lifecycle transition. */
    public static ProjectionGenerationState reconstitute(
            ProjectionGenerationKey key,
            ProjectionDefinitionVersion definitionVersion,
            Optional<ProjectionRebuildJobId> rebuildJobId,
            ProjectionGenerationStatus status,
            ProjectionFencingToken fencingToken,
            Optional<ProjectionValidationResult> validation,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            long version) {
        return new ProjectionGenerationState(
                key, definitionVersion, rebuildJobId, status, fencingToken, validation,
                createdAt, updatedAt, version);
    }

    /** Saves every validation attempt and enters VALIDATING only after a successful comparison. */
    public ProjectionGenerationState recordValidation(
            long expectedVersion,
            ProjectionValidationResult result,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status != ProjectionGenerationStatus.BUILDING
                && status != ProjectionGenerationStatus.VALIDATING) {
            throw new IllegalStateException("Only a shadow Generation can be validated");
        }
        ProjectionValidationResult required = Objects.requireNonNull(result, "result");
        requireValidationBinding(required);
        ProjectionGenerationStatus next = required.passed()
                ? ProjectionGenerationStatus.VALIDATING
                : status;
        return transition(next, Optional.of(required), occurredAt);
    }

    /** Activates only a successfully validated target whose fresh canonical snapshot is unchanged. */
    public ProjectionGenerationState activate(
            long expectedVersion,
            ProjectionSnapshot currentSnapshot,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status != ProjectionGenerationStatus.VALIDATING) {
            throw new IllegalStateException("Only a VALIDATING Generation can become ACTIVE");
        }
        validation.orElseThrow(() -> new IllegalStateException("Projection validation is missing"))
                .requireCurrent(currentSnapshot);
        return transition(ProjectionGenerationStatus.ACTIVE, validation, occurredAt);
    }

    public ProjectionGenerationState retire(long expectedVersion, UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status != ProjectionGenerationStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE Generation can be retired");
        }
        return transition(ProjectionGenerationStatus.RETIRED, validation, occurredAt);
    }

    public ProjectionGenerationState fail(long expectedVersion, UtcTimestamp occurredAt) {
        return terminate(expectedVersion, ProjectionGenerationStatus.FAILED, occurredAt);
    }

    public ProjectionGenerationState cancel(long expectedVersion, UtcTimestamp occurredAt) {
        return terminate(expectedVersion, ProjectionGenerationStatus.CANCELLED, occurredAt);
    }

    /** Workers must call this inside the same transaction that writes Receipt/Checkpoint/rows. */
    public void requireWritableBy(ProjectionGenerationLease lease) {
        ProjectionGenerationLease required = Objects.requireNonNull(lease, "lease");
        if (!key.equals(required.key())
                || !status.acceptsWrites()
                || !fencingToken.equals(required.fencingToken())) {
            throw new IllegalStateException("Projection Generation lease is stale or not writable");
        }
    }

    public void requireCheckpoint(ProjectionCheckpointReference reference) {
        if (!key.equals(Objects.requireNonNull(reference, "reference").generationKey())) {
            throw new IllegalArgumentException("Projection Checkpoint belongs to another Generation");
        }
    }

    public void requireDeadLetter(ProjectionDeadLetterReference reference) {
        if (!key.equals(Objects.requireNonNull(reference, "reference").generationKey())) {
            throw new IllegalArgumentException("Projection Dead Letter belongs to another Generation");
        }
    }

    private ProjectionGenerationState terminate(
            long expectedVersion,
            ProjectionGenerationStatus terminal,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (!status.shadow()) {
            throw new IllegalStateException("Only a non-terminal shadow Generation can terminate");
        }
        return transition(terminal, validation, occurredAt);
    }

    private ProjectionGenerationState transition(
            ProjectionGenerationStatus next,
            Optional<ProjectionValidationResult> nextValidation,
            UtcTimestamp occurredAt) {
        return new ProjectionGenerationState(
                key, definitionVersion, rebuildJobId, next, fencingToken.next(), nextValidation,
                createdAt, Objects.requireNonNull(occurredAt, "occurredAt"), version + 1);
    }

    private void requireValidationBinding(ProjectionValidationResult result) {
        if (!definitionVersion.equals(result.definitionVersion())
                || !key.generation().equals(result.generation())
                || rebuildJobId.filter(result.rebuildJobId()::equals).isEmpty()) {
            throw new IllegalArgumentException(
                    "Projection validation must bind the exact Definition, Generation and Job");
        }
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new IllegalStateException(
                    "Projection Generation version conflict: expected "
                            + expectedVersion + ", actual " + version);
        }
    }

    private void validateShape() {
        if (status.shadow() && rebuildJobId.isEmpty()) {
            throw new IllegalArgumentException("A shadow Generation must reference its RebuildJob");
        }
        validation.ifPresent(this::requireValidationBinding);
        if (status == ProjectionGenerationStatus.VALIDATING
                && validation.filter(ProjectionValidationResult::passed).isEmpty()) {
            throw new IllegalArgumentException("A VALIDATING Generation needs a successful result");
        }
    }

    public ProjectionGenerationKey key() { return key; }
    public ProjectionDefinitionVersion definitionVersion() { return definitionVersion; }
    public Optional<ProjectionRebuildJobId> rebuildJobId() { return rebuildJobId; }
    public ProjectionGenerationStatus status() { return status; }
    public ProjectionFencingToken fencingToken() { return fencingToken; }
    public Optional<ProjectionValidationResult> validation() { return validation; }
    public UtcTimestamp createdAt() { return createdAt; }
    public UtcTimestamp updatedAt() { return updatedAt; }
    public long version() { return version; }

    public ProjectionGenerationLease lease() {
        if (!status.acceptsWrites()) {
            throw new IllegalStateException("Terminal Generation cannot issue a Worker lease");
        }
        return new ProjectionGenerationLease(key, fencingToken);
    }
}
