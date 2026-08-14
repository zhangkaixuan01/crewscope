package io.crewscope.domain.task;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Creation-time responsibility facts used throughout one Task's durable execution. */
public record TaskResponsibilitySnapshot(
        WorkItemScope scope,
        WorkItemId workItemId,
        List<TaskResponsibilitySnapshotEntry> entries,
        UtcTimestamp capturedAt) {

    public TaskResponsibilitySnapshot {
        scope = Objects.requireNonNull(scope, "scope");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        entries = requireCompleteEntries(entries);
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        for (TaskResponsibilitySnapshotEntry entry : entries) {
            if (entry.acceptedAt().compareTo(capturedAt) > 0) {
                throw new DomainValidationException(
                        "taskResponsibilitySnapshot.capturedAt",
                        "must not be before an Assignment acceptedAt");
            }
        }
    }

    /** Copies all current active responsibility facts and rejects an unexecutable responsibility set. */
    public static TaskResponsibilitySnapshot capture(
            WorkItem workItem,
            Collection<ResponsibilityAssignment> activeAssignments,
            UtcTimestamp capturedAt) {
        WorkItem requiredWorkItem = Objects.requireNonNull(workItem, "workItem");
        List<TaskResponsibilitySnapshotEntry> entries = List.copyOf(
                Objects.requireNonNull(activeAssignments, "activeAssignments"))
                .stream()
                .map(assignment ->
                        TaskResponsibilitySnapshotEntry.capture(requiredWorkItem, assignment))
                .toList();
        return new TaskResponsibilitySnapshot(
                requiredWorkItem.scope(), requiredWorkItem.id(), entries, capturedAt);
    }

    public List<TaskResponsibilitySnapshotEntry> byRole(ResponsibilityRole role) {
        ResponsibilityRole required = Objects.requireNonNull(role, "role");
        return entries.stream().filter(entry -> entry.role() == required).toList();
    }

    private static List<TaskResponsibilitySnapshotEntry> requireCompleteEntries(
            List<TaskResponsibilitySnapshotEntry> values) {
        List<TaskResponsibilitySnapshotEntry> required = List.copyOf(
                Objects.requireNonNull(values, "entries"));
        if (required.isEmpty()) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.entries", "must not be empty");
        }
        Set<ResponsibilityAssignmentId> assignmentIds = new HashSet<>();
        long owners = 0;
        long executors = 0;
        for (TaskResponsibilitySnapshotEntry entry : required) {
            if (!assignmentIds.add(entry.assignmentId())) {
                throw new DomainValidationException(
                        "taskResponsibilitySnapshot.entries",
                        "must not contain duplicate Assignment IDs");
            }
            if (entry.role() == ResponsibilityRole.OWNER) {
                owners++;
            } else if (entry.role() == ResponsibilityRole.EXECUTOR) {
                executors++;
            }
        }
        if (owners != 1) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.owner", "must contain exactly one Owner");
        }
        if (executors < 1) {
            throw new DomainValidationException(
                    "taskResponsibilitySnapshot.executor", "must contain at least one Executor");
        }
        return required;
    }
}
