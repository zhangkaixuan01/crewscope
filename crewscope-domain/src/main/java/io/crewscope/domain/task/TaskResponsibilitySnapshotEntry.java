package io.crewscope.domain.task;

import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Objects;
import java.util.Optional;

/** Immutable responsibility evidence copied from one active Assignment at Task creation. */
public record TaskResponsibilitySnapshotEntry(
        ResponsibilityAssignmentId assignmentId,
        long assignmentVersion,
        ResponsibilityRole role,
        PrincipalId principalId,
        PrincipalType principalType,
        Optional<TeamMemberId> memberId,
        UtcTimestamp assignedAt,
        UtcTimestamp acceptedAt) {

    public TaskResponsibilitySnapshotEntry {
        assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        if (assignmentVersion < 0) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.assignmentVersion", "must not be negative");
        }
        role = Objects.requireNonNull(role, "role");
        principalId = Objects.requireNonNull(principalId, "principalId");
        principalType = Objects.requireNonNull(principalType, "principalType");
        memberId = Objects.requireNonNull(memberId, "memberId");
        assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (acceptedAt.compareTo(assignedAt) < 0) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.acceptedAt", "must not be before assignedAt");
        }
        if ((principalType == PrincipalType.USER) != memberId.isPresent()) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.memberId",
                    principalType == PrincipalType.USER
                            ? "is required for a USER responsibility"
                            : "must be empty for an Agent responsibility");
        }
        boolean allowedType = switch (role) {
            case OWNER -> principalType == PrincipalType.USER;
            case EXECUTOR -> principalType == PrincipalType.USER || principalType.isAgent();
            case REVIEWER ->
                principalType == PrincipalType.USER
                        || principalType == PrincipalType.SPECIALIST_AGENT;
        };
        if (!allowedType) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.principalId",
                    "Principal type " + principalType + " cannot hold role " + role);
        }
    }

    static TaskResponsibilitySnapshotEntry capture(
            WorkItem workItem, ResponsibilityAssignment assignment) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        ResponsibilityAssignment required = Objects.requireNonNull(assignment, "assignment");
        if (!required.isActive()
                || !required.scope().equals(requiredWorkItem.scope())
                || !required.workItemId().equals(requiredWorkItem.id())) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.assignments",
                    "must contain active Assignments for the source WorkItem");
        }
        return new TaskResponsibilitySnapshotEntry(
                required.id(),
                required.version(),
                required.role(),
                required.actorPrincipalId(),
                required.actorType(),
                required.actorMemberId(),
                required.assignedAt(),
                required.acceptedAt());
    }
}
