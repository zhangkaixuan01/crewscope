package io.crewscope.domain.projection;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Four-row state result that the persistence adapter commits with Pointer-first locking. */
public record ProjectionSwitchPlan(
        ProjectionGenerationState retiredPrevious,
        ProjectionGenerationState activatedTarget,
        ProjectionPointer pointer,
        ProjectionRebuildJob completedJob) {

    public ProjectionSwitchPlan {
        retiredPrevious = Objects.requireNonNull(retiredPrevious, "retiredPrevious");
        activatedTarget = Objects.requireNonNull(activatedTarget, "activatedTarget");
        pointer = Objects.requireNonNull(pointer, "pointer");
        completedJob = Objects.requireNonNull(completedJob, "completedJob");
    }

    public static ProjectionSwitchPlan switchValidated(
            ProjectionPointer pointer,
            ProjectionGenerationState previousActive,
            ProjectionGenerationState target,
            ProjectionRebuildJob job,
            ProjectionSnapshot currentTargetSnapshot,
            long expectedPointerVersion,
            long expectedPreviousVersion,
            long expectedTargetVersion,
            long expectedJobVersion,
            UtcTimestamp occurredAt) {
        ProjectionPointer currentPointer = Objects.requireNonNull(pointer, "pointer");
        ProjectionGenerationState currentPrevious = Objects.requireNonNull(
                previousActive, "previousActive");
        ProjectionGenerationState currentTarget = Objects.requireNonNull(target, "target");
        ProjectionRebuildJob currentJob = Objects.requireNonNull(job, "job");
        requireBinding(currentPointer, currentPrevious, currentTarget, currentJob);

        // The fresh snapshot is checked before any returned object can move the Pointer.
        ProjectionGenerationState activated = currentTarget.activate(
                expectedTargetVersion, currentTargetSnapshot, occurredAt);
        ProjectionGenerationState retired = currentPrevious.retire(
                expectedPreviousVersion, occurredAt);
        ProjectionPointer switched = currentPointer.switchTo(
                expectedPointerVersion, activated, occurredAt);
        ProjectionRebuildJob completed = currentJob.complete(expectedJobVersion, occurredAt);
        return new ProjectionSwitchPlan(retired, activated, switched, completed);
    }

    private static void requireBinding(
            ProjectionPointer pointer,
            ProjectionGenerationState previous,
            ProjectionGenerationState target,
            ProjectionRebuildJob job) {
        ProjectionGenerationKey previousKey = previous.key();
        ProjectionGenerationKey targetKey = target.key();
        if (!pointer.organizationId().equals(previousKey.organizationId())
                || !pointer.organizationId().equals(targetKey.organizationId())
                || !pointer.projectionName().equals(previousKey.projectionName())
                || !pointer.projectionName().equals(targetKey.projectionName())
                || !pointer.activeGeneration().equals(previousKey.generation())
                || previous.status() != ProjectionGenerationStatus.ACTIVE
                || target.status() != ProjectionGenerationStatus.VALIDATING
                || !job.organizationId().equals(targetKey.organizationId())
                || !job.projectionName().equals(targetKey.projectionName())
                || !job.generation().equals(targetKey.generation())
                || target.rebuildJobId().filter(job.id()::equals).isEmpty()) {
            throw new IllegalStateException(
                    "Switch Pointer, ACTIVE Generation, target Generation and Job must match");
        }
    }
}
