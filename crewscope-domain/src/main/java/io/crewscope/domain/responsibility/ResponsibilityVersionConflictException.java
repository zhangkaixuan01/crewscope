package io.crewscope.domain.responsibility;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reports that the caller's view of a WorkItem responsibility slot is stale. */
public final class ResponsibilityVersionConflictException extends DomainException {

    public ResponsibilityVersionConflictException(
            WorkItemId workItemId,
            ResponsibilityRole role,
            ActiveOwnerExpectation expected,
            Optional<ResponsibilityAssignment> actual) {
        super(error(workItemId, role, expected, actual));
    }

    private static DomainError error(
            WorkItemId workItemId,
            ResponsibilityRole role,
            ActiveOwnerExpectation expected,
            Optional<ResponsibilityAssignment> actual) {
        WorkItemId requiredWorkItemId = Objects.requireNonNull(workItemId, "workItemId");
        ResponsibilityRole requiredRole = Objects.requireNonNull(role, "role");
        ActiveOwnerExpectation requiredExpected = Objects.requireNonNull(expected, "expected");
        Optional<ResponsibilityAssignment> requiredActual =
                Objects.requireNonNull(actual, "actual");
        String expectedId = requiredExpected.assignmentId()
                .map(ResponsibilityAssignmentId::toString)
                .orElse("NONE");
        String actualId = requiredActual
                .map(ResponsibilityAssignment::id)
                .map(ResponsibilityAssignmentId::toString)
                .orElse("NONE");
        String expectedVersion = versionText(
                requiredExpected.assignmentId().isPresent(),
                requiredExpected.assignmentVersion());
        String actualVersion = requiredActual
                .map(value -> Long.toString(value.version()))
                .orElse("NONE");
        return new DomainError(
                DomainErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "WorkItem %s %s responsibility conflict: expected %s@%s, actual %s@%s"
                        .formatted(
                                requiredWorkItemId,
                                requiredRole,
                                expectedId,
                                expectedVersion,
                                actualId,
                                actualVersion),
                Map.of(
                        "aggregateType", "ResponsibilityAssignment",
                        "workItemId", requiredWorkItemId.toString(),
                        "role", requiredRole.name(),
                        "expectedAssignmentId", expectedId,
                        "actualAssignmentId", actualId,
                        "expectedVersion", expectedVersion,
                        "actualVersion", actualVersion));
    }

    private static String versionText(boolean present, long version) {
        return present ? Long.toString(version) : "NONE";
    }
}
