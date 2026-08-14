package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.List;
import java.util.Objects;

/** Executor identity pinned to the exact responsibility fact used by an execution attempt. */
public record ExecutionPrincipalSnapshot(
        PrincipalId principalId,
        ResponsibilityAssignmentId assignmentId,
        long assignmentVersion,
        TaskFactHash responsibilitySnapshotHash) {

    public ExecutionPrincipalSnapshot {
        principalId = Objects.requireNonNull(principalId, "principalId");
        assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        if (assignmentVersion < 0) {
            throw new DomainValidationException(
                    "executionPrincipal.assignmentVersion", "must not be negative");
        }
        responsibilitySnapshotHash = Objects.requireNonNull(
                responsibilitySnapshotHash, "responsibilitySnapshotHash");
    }

    /** Resolves one active Principal from a captured EXECUTOR responsibility. */
    public static ExecutionPrincipalSnapshot resolve(
            TaskResponsibilitySnapshot responsibilitySnapshot,
            Principal executor) {
        TaskResponsibilitySnapshot snapshot = Objects.requireNonNull(
                responsibilitySnapshot, "responsibilitySnapshot");
        Principal requiredExecutor = Objects.requireNonNull(executor, "executor");
        WorkItemScope scope = snapshot.scope();
        TaskActorPolicy.requireActiveInScope(
                requiredExecutor, scope, "executionPrincipal.principalId");
        List<TaskResponsibilitySnapshotEntry> matches = snapshot.byRole(ResponsibilityRole.EXECUTOR)
                .stream()
                .filter(entry -> entry.principalId().equals(requiredExecutor.id()))
                .toList();
        if (matches.size() != 1) {
            throw new DomainValidationException(
                    "executionPrincipal.principalId",
                    "must identify exactly one captured Executor responsibility");
        }
        TaskResponsibilitySnapshotEntry match = matches.get(0);
        if (match.principalType() != requiredExecutor.type()) {
            throw new DomainValidationException(
                    "executionPrincipal.principalId",
                    "must match the captured Executor Principal type");
        }
        return new ExecutionPrincipalSnapshot(
                match.principalId(),
                match.assignmentId(),
                match.assignmentVersion(),
                hash(snapshot));
    }

    static TaskFactHash hash(TaskResponsibilitySnapshot snapshot) {
        StringBuilder canonical = new StringBuilder()
                .append(snapshot.scope().organizationId()).append('|')
                .append(snapshot.scope().teamId()).append('|')
                .append(snapshot.scope().workspaceId()).append('|')
                .append(snapshot.scope().projectId()).append('|')
                .append(snapshot.workItemId()).append('|')
                .append(snapshot.capturedAt());
        snapshot.entries().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.assignmentId().toString()))
                .forEach(entry -> canonical.append('|')
                        .append(entry.assignmentId()).append(':')
                        .append(entry.assignmentVersion()).append(':')
                        .append(entry.role()).append(':')
                        .append(entry.principalId()).append(':')
                        .append(entry.principalType()).append(':')
                        .append(entry.memberId().map(Object::toString).orElse("-")));
        return TaskFactHash.sha256(canonical.toString());
    }
}
