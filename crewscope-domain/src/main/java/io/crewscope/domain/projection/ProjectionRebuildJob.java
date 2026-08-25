package io.crewscope.domain.projection;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Durable rebuild attempt; retry creates another Job and Generation instead of reviving this one. */
public final class ProjectionRebuildJob {

    private final ProjectionRebuildJobId id;
    private final OrganizationId organizationId;
    private final ProjectionName projectionName;
    private final ProjectionDefinitionVersion definitionVersion;
    private final ProjectionGeneration generation;
    private final Optional<ProjectionRebuildJobId> retryOf;
    private final PrincipalId requestedBy;
    private final ProjectionRebuildStatus status;
    private final Optional<ProjectionValidationResult> validation;
    private final UtcTimestamp createdAt;
    private final UtcTimestamp updatedAt;
    private final long version;

    private ProjectionRebuildJob(
            ProjectionRebuildJobId id,
            OrganizationId organizationId,
            ProjectionName projectionName,
            ProjectionDefinitionVersion definitionVersion,
            ProjectionGeneration generation,
            Optional<ProjectionRebuildJobId> retryOf,
            PrincipalId requestedBy,
            ProjectionRebuildStatus status,
            Optional<ProjectionValidationResult> validation,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.projectionName = Objects.requireNonNull(projectionName, "projectionName");
        this.definitionVersion = Objects.requireNonNull(definitionVersion, "definitionVersion");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.retryOf = Objects.requireNonNull(retryOf, "retryOf");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.status = Objects.requireNonNull(status, "status");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0 || updatedAt.compareTo(createdAt) < 0 || retryOf.filter(id::equals).isPresent()) {
            throw new IllegalArgumentException("Projection RebuildJob shape is invalid");
        }
        this.version = version;
        validateShape();
    }

    public static ProjectionRebuildJob start(
            ProjectionRebuildJobId id,
            OrganizationId organizationId,
            ProjectionDefinition definition,
            ProjectionGeneration generation,
            Optional<ProjectionRebuildJobId> retryOf,
            PrincipalId requestedBy,
            UtcTimestamp createdAt) {
        ProjectionDefinition required = Objects.requireNonNull(definition, "definition");
        return new ProjectionRebuildJob(
                id, organizationId, required.name(), required.version(), generation, retryOf,
                requestedBy, ProjectionRebuildStatus.BUILDING, Optional.empty(), createdAt,
                createdAt, 0);
    }

    /** Reconstitutes a persisted Job without reviving or advancing its lifecycle. */
    public static ProjectionRebuildJob reconstitute(
            ProjectionRebuildJobId id,
            OrganizationId organizationId,
            ProjectionName projectionName,
            ProjectionDefinitionVersion definitionVersion,
            ProjectionGeneration generation,
            Optional<ProjectionRebuildJobId> retryOf,
            PrincipalId requestedBy,
            ProjectionRebuildStatus status,
            Optional<ProjectionValidationResult> validation,
            UtcTimestamp createdAt,
            UtcTimestamp updatedAt,
            long version) {
        return new ProjectionRebuildJob(
                id, organizationId, projectionName, definitionVersion, generation, retryOf,
                requestedBy, status, validation, createdAt, updatedAt, version);
    }

    public ProjectionRebuildJob recordValidation(
            long expectedVersion,
            ProjectionValidationResult result,
            UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status != ProjectionRebuildStatus.BUILDING
                && status != ProjectionRebuildStatus.VALIDATING) {
            throw new IllegalStateException("Terminal RebuildJob cannot be validated");
        }
        ProjectionValidationResult required = requireValidation(result);
        ProjectionRebuildStatus next = required.passed()
                ? ProjectionRebuildStatus.VALIDATING
                : status;
        return transition(next, Optional.of(required), occurredAt);
    }

    public ProjectionRebuildJob complete(long expectedVersion, UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status != ProjectionRebuildStatus.VALIDATING
                || validation.filter(ProjectionValidationResult::passed).isEmpty()) {
            throw new IllegalStateException("Only a successfully validated RebuildJob can complete");
        }
        return transition(ProjectionRebuildStatus.COMPLETED, validation, occurredAt);
    }

    public ProjectionRebuildJob fail(long expectedVersion, UtcTimestamp occurredAt) {
        return terminate(expectedVersion, ProjectionRebuildStatus.FAILED, occurredAt);
    }

    public ProjectionRebuildJob cancel(long expectedVersion, UtcTimestamp occurredAt) {
        return terminate(expectedVersion, ProjectionRebuildStatus.CANCELLED, occurredAt);
    }

    private ProjectionRebuildJob terminate(
            long expectedVersion, ProjectionRebuildStatus target, UtcTimestamp occurredAt) {
        requireVersion(expectedVersion);
        if (status.terminal()) {
            throw new IllegalStateException("Terminal RebuildJob is immutable");
        }
        return transition(target, validation, occurredAt);
    }

    private ProjectionRebuildJob transition(
            ProjectionRebuildStatus next,
            Optional<ProjectionValidationResult> nextValidation,
            UtcTimestamp occurredAt) {
        return new ProjectionRebuildJob(
                id, organizationId, projectionName, definitionVersion, generation, retryOf,
                requestedBy, next, nextValidation, createdAt,
                Objects.requireNonNull(occurredAt, "occurredAt"), version + 1);
    }

    private ProjectionValidationResult requireValidation(ProjectionValidationResult value) {
        ProjectionValidationResult required = Objects.requireNonNull(value, "validation");
        if (!definitionVersion.equals(required.definitionVersion())
                || !generation.equals(required.generation())
                || !id.equals(required.rebuildJobId())) {
            throw new IllegalArgumentException("Validation belongs to another RebuildJob");
        }
        return required;
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new IllegalStateException(
                    "Projection RebuildJob version conflict: expected "
                            + expectedVersion + ", actual " + version);
        }
    }

    private void validateShape() {
        validation.ifPresent(this::requireValidation);
        if (status == ProjectionRebuildStatus.VALIDATING
                && validation.filter(ProjectionValidationResult::passed).isEmpty()) {
            throw new IllegalArgumentException("A VALIDATING RebuildJob needs a successful result");
        }
        if (status == ProjectionRebuildStatus.COMPLETED
                && validation.filter(ProjectionValidationResult::passed).isEmpty()) {
            throw new IllegalArgumentException("A COMPLETED RebuildJob needs a successful result");
        }
    }

    public ProjectionRebuildJobId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public ProjectionName projectionName() { return projectionName; }
    public ProjectionDefinitionVersion definitionVersion() { return definitionVersion; }
    public ProjectionGeneration generation() { return generation; }
    public Optional<ProjectionRebuildJobId> retryOf() { return retryOf; }
    public PrincipalId requestedBy() { return requestedBy; }
    public ProjectionRebuildStatus status() { return status; }
    public Optional<ProjectionValidationResult> validation() { return validation; }
    public UtcTimestamp createdAt() { return createdAt; }
    public UtcTimestamp updatedAt() { return updatedAt; }
    public long version() { return version; }
}
