package io.crewscope.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectionRebuildDomainM6D07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-25T13:00:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-25T13:01:00Z");
    private static final ProjectionCanonicalHash HASH_A =
            new ProjectionCanonicalHash("a".repeat(64));
    private static final ProjectionCanonicalHash HASH_B =
            new ProjectionCanonicalHash("b".repeat(64));

    private OrganizationId organizationId;
    private PrincipalId actorId;
    private ProjectionDefinition definition;
    private ProjectionGenerationState active;
    private ProjectionPointer pointer;

    @BeforeEach
    void setUp() {
        organizationId = OrganizationId.generate();
        actorId = PrincipalId.generate();
        definition = new ProjectionDefinition(
                new ProjectionName("team-activity"),
                ProjectionDefinitionVersion.V1,
                SchemaVersion.V1,
                "activity.canonical-v1",
                "activity.validator-v1");
        active = ProjectionGenerationState.active(
                organizationId, definition, ProjectionGeneration.FIRST, NOW);
        pointer = ProjectionPointer.initialize(active, NOW);
    }

    @Test
    void createsOnlyOneShadowAfterVerifyingTheSingleActivePointer() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));

        assertEquals(new ProjectionGeneration(2), start.generation().key().generation());
        assertEquals(ProjectionGenerationStatus.BUILDING, start.generation().status());
        assertEquals(ProjectionRebuildStatus.BUILDING, start.job().status());
        assertEquals(pointer.version(), start.expectedPointerVersion());
        assertThrows(
                IllegalStateException.class,
                () -> start(Optional.empty(), List.of(active, start.generation())));
    }

    @Test
    void validatesAndBuildsOneAtomicSwitchPlan() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));
        ProjectionSnapshot snapshot = healthy(7, HASH_A);
        ProjectionValidationPlan validation = ProjectionValidationPlan.validate(
                definition,
                start.generation(),
                start.job(),
                0,
                0,
                snapshot,
                snapshot,
                actorId,
                LATER);

        ProjectionGenerationLease validationLease = validation.generation().lease();
        ProjectionSwitchPlan switched = ProjectionSwitchPlan.switchValidated(
                pointer,
                active,
                validation.generation(),
                validation.job(),
                snapshot,
                0,
                0,
                1,
                1,
                LATER);

        assertTrue(validation.result().passed());
        assertEquals(ProjectionGenerationStatus.RETIRED, switched.retiredPrevious().status());
        assertEquals(ProjectionGenerationStatus.ACTIVE, switched.activatedTarget().status());
        assertEquals(ProjectionRebuildStatus.COMPLETED, switched.completedJob().status());
        assertEquals(new ProjectionGeneration(2), switched.pointer().activeGeneration());
        assertThrows(
                IllegalStateException.class,
                () -> switched.activatedTarget().requireWritableBy(validationLease));
    }

    @Test
    void gapOrFailedPartitionCannotEnterValidatingOrSwitch() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));
        ProjectionFailedPartition failure = new ProjectionFailedPartition(
                HASH_B, new ProjectionFailureCode("PROJECTION_GAP"));
        ProjectionSnapshot unhealthy = new ProjectionSnapshot(3, HASH_A, 1, List.of(failure));
        ProjectionValidationPlan validation = ProjectionValidationPlan.validate(
                definition, start.generation(), start.job(), 0, 0,
                healthy(3, HASH_A), unhealthy, actorId, LATER);

        assertFalse(validation.result().passed());
        assertEquals(ProjectionGenerationStatus.BUILDING, validation.generation().status());
        assertThrows(
                IllegalStateException.class,
                () -> ProjectionSwitchPlan.switchValidated(
                        pointer, active, validation.generation(), validation.job(), unhealthy,
                        0, 0, 1, 1, LATER));
    }

    @Test
    void changedSnapshotAfterValidationRequiresAnotherValidationAttempt() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));
        ProjectionSnapshot validated = healthy(2, HASH_A);
        ProjectionValidationPlan validation = ProjectionValidationPlan.validate(
                definition, start.generation(), start.job(), 0, 0,
                validated, validated, actorId, LATER);

        assertThrows(
                IllegalStateException.class,
                () -> ProjectionSwitchPlan.switchValidated(
                        pointer, active, validation.generation(), validation.job(),
                        healthy(3, HASH_B), 0, 0, 1, 1, LATER));

        ProjectionValidationPlan refreshed = ProjectionValidationPlan.validate(
                definition, validation.generation(), validation.job(), 1, 1,
                healthy(3, HASH_B), healthy(3, HASH_B), actorId, LATER);
        ProjectionSwitchPlan switched = ProjectionSwitchPlan.switchValidated(
                pointer, active, refreshed.generation(), refreshed.job(), healthy(3, HASH_B),
                0, 0, 2, 2, LATER);
        assertEquals(ProjectionGenerationStatus.ACTIVE, switched.activatedTarget().status());
    }

    @Test
    void lifecycleTransitionsFenceOldWorkersAndTerminalStateCannotRecover() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));
        ProjectionGenerationLease buildingLease = start.generation().lease();
        ProjectionSnapshot snapshot = healthy(1, HASH_A);
        ProjectionValidationPlan validation = ProjectionValidationPlan.validate(
                definition, start.generation(), start.job(), 0, 0,
                snapshot, snapshot, actorId, LATER);

        assertThrows(
                IllegalStateException.class,
                () -> validation.generation().requireWritableBy(buildingLease));
        ProjectionTerminationPlan failed = ProjectionTerminationPlan.fail(
                validation.generation(), validation.job(), 1, 1, LATER);
        assertEquals(ProjectionGenerationStatus.FAILED, failed.generation().status());
        assertThrows(
                IllegalStateException.class,
                () -> failed.generation().recordValidation(
                        2, validation.result(), LATER));
        assertThrows(IllegalStateException.class, failed.generation()::lease);
    }

    @Test
    void retryCreatesNewGenerationAndJobWithoutMutatingFailedAttempt() {
        ProjectionRebuildStart first = start(Optional.empty(), List.of(active));
        ProjectionTerminationPlan failed = ProjectionTerminationPlan.fail(
                first.generation(), first.job(), 0, 0, LATER);

        ProjectionRebuildStart retry = start(
                Optional.of(failed.job()), List.of(active, failed.generation()));

        assertEquals(new ProjectionGeneration(3), retry.generation().key().generation());
        assertEquals(Optional.of(first.job().id()), retry.job().retryOf());
        assertEquals(ProjectionRebuildStatus.FAILED, failed.job().status());
        assertEquals(ProjectionGenerationStatus.FAILED, failed.generation().status());
    }

    @Test
    void rejectsOptimisticVersionAndCrossGenerationReferences() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));
        ProjectionGenerationKey otherKey = new ProjectionGenerationKey(
                organizationId, definition.name(), new ProjectionGeneration(99));

        assertThrows(
                IllegalStateException.class,
                () -> start.generation().cancel(2, LATER));
        assertThrows(
                IllegalArgumentException.class,
                () -> start.generation().requireCheckpoint(
                        new ProjectionCheckpointReference(otherKey, "work-item:1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> start.generation().requireDeadLetter(new ProjectionDeadLetterReference(
                        ProjectionDeadLetterId.generate(), otherKey, HASH_A,
                        new ProjectionFailureCode("PROJECTOR_FAILED"))));
    }

    @Test
    void validationMustBindExactDefinitionGenerationJobAndActor() {
        ProjectionRebuildStart start = start(Optional.empty(), List.of(active));
        ProjectionValidationResult wrongJob = new ProjectionValidationResult(
                definition.version(), start.generation().key().generation(),
                ProjectionRebuildJobId.generate(), healthy(1, HASH_A), healthy(1, HASH_A),
                actorId, LATER);

        assertThrows(
                IllegalArgumentException.class,
                () -> start.generation().recordValidation(0, wrongJob, LATER));
        assertThrows(
                IllegalArgumentException.class,
                () -> start.job().recordValidation(0, wrongJob, LATER));
    }

    private ProjectionRebuildStart start(
            Optional<ProjectionRebuildJob> retryOf,
            List<ProjectionGenerationState> generations) {
        return ProjectionRebuildStart.start(
                organizationId,
                definition,
                pointer,
                generations,
                ProjectionRebuildJobId.generate(),
                retryOf,
                actorId,
                NOW);
    }

    private static ProjectionSnapshot healthy(long count, ProjectionCanonicalHash hash) {
        return new ProjectionSnapshot(count, hash, 0, List.of());
    }
}
