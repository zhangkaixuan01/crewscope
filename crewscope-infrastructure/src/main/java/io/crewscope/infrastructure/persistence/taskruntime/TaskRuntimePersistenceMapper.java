package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.ExecutionRuntimeStatus;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.runtime.RuntimeWorkerStatus;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLink;
import io.crewscope.domain.task.ConversationTaskLinkId;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import io.crewscope.domain.task.ExecutionPrincipalSnapshot;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskCancellation;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionControlRequest;
import io.crewscope.domain.task.TaskExecutionControlRequestType;
import io.crewscope.domain.task.TaskExecutionFailure;
import io.crewscope.domain.task.TaskExecutionFailureClass;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionPriority;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionTerminal;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskExecutionWaiting;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskInputReference;
import io.crewscope.domain.task.TaskInputReferenceType;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskResponsibilitySnapshotEntry;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.TaskSourceType;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Bidirectional mapping between V10 scalar rows and validated M3 domain aggregates. */
@Component
public class TaskRuntimePersistenceMapper {

    TaskResponsibilitySnapshotEntity toSnapshotEntity(Task task) {
        Task required = Objects.requireNonNull(task, "task");
        TaskResponsibilitySnapshot snapshot = required.responsibilitySnapshot();
        TaskResponsibilitySnapshotEntity row = new TaskResponsibilitySnapshotEntity();
        row.id = snapshotId(required.id());
        putScope(row, required.scope());
        row.workItemId = required.workItemId().value();
        row.snapshotHash = responsibilityHash(snapshot).value();
        row.capturedAt = snapshot.capturedAt().value();
        row.version = 0;
        row.createdAt = required.audit().createdAt().value();
        row.createdByPrincipalId = principal(required.audit().createdBy(), "task.createdBy");
        row.updatedAt = required.audit().createdAt().value();
        row.updatedByPrincipalId = row.createdByPrincipalId;
        return row;
    }

    TaskEntity toTaskEntity(Task task) {
        Task required = Objects.requireNonNull(task, "task");
        TaskEntity row = new TaskEntity();
        row.id = required.id().value();
        putScope(row, required.scope());
        row.workItemId = required.workItemId().value();
        TaskSource source = required.source();
        row.sourceType = source.type().name();
        row.sourceWorkItemVersion = source.workItemVersion();
        row.sourceConversationId = source.conversationId().map(ConversationId::value).orElse(null);
        source.inputReference().ifPresent(reference -> {
            row.sourceInputType = reference.type().name();
            row.sourceInputId = reference.referenceId();
            row.sourceInputVersion = reference.referenceVersion();
        });
        row.objective = required.brief().objective();
        row.acceptanceCriteria = required.brief().acceptanceCriteria();
        row.responsibilitySnapshotId = snapshotId(required.id());
        copyTaskState(row, required);
        putAudit(row, required.audit(), required.version());
        return row;
    }

    void copyTaskState(TaskEntity row, Task task) {
        row.status = task.status().name();
        row.currentExecutionId = task.currentExecutionId().map(TaskExecutionId::value).orElse(null);
        TaskCancellation cancellation = task.cancellation().orElse(null);
        row.cancelledByPrincipalId = cancellation == null
                ? null : cancellation.cancelledByPrincipalId().value();
        row.cancelledAt = cancellation == null ? null : cancellation.cancelledAt().value();
        row.cancellationReason = cancellation == null ? null : cancellation.reason();
        row.updatedAt = task.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(task.audit().updatedBy(), "task.updatedBy");
    }

    Task toTaskDomain(
            TaskEntity row,
            TaskResponsibilitySnapshotEntity snapshotRow,
            List<TaskResponsibilitySnapshotEntry> entries) {
        WorkItemScope scope = scope(row);
        TaskSource source = new TaskSource(
                TaskSourceType.valueOf(row.sourceType),
                scope,
                new WorkItemId(row.workItemId),
                row.sourceWorkItemVersion,
                optionalId(row.sourceConversationId, ConversationId::new),
                row.sourceInputId == null
                        ? Optional.empty()
                        : Optional.of(new TaskInputReference(
                                TaskInputReferenceType.valueOf(row.sourceInputType),
                                row.sourceInputId,
                                row.sourceInputVersion)));
        TaskResponsibilitySnapshot snapshot = new TaskResponsibilitySnapshot(
                scope,
                new WorkItemId(row.workItemId),
                entries,
                new UtcTimestamp(snapshotRow.capturedAt));
        Optional<TaskCancellation> cancellation = row.cancelledAt == null
                ? Optional.empty()
                : Optional.of(new TaskCancellation(
                        new PrincipalId(row.cancelledByPrincipalId),
                        new UtcTimestamp(row.cancelledAt),
                        row.cancellationReason));
        return Task.reconstitute(
                new TaskId(row.id),
                scope,
                new WorkItemId(row.workItemId),
                source,
                new TaskBrief(row.objective, row.acceptanceCriteria),
                snapshot,
                TaskStatus.valueOf(row.status),
                optionalId(row.currentExecutionId, TaskExecutionId::new),
                cancellation,
                row.version,
                audit(row));
    }

    ConversationTaskLinkEntity toLinkEntity(ConversationTaskLink link) {
        ConversationTaskLink required = Objects.requireNonNull(link, "link");
        ConversationTaskLinkEntity row = new ConversationTaskLinkEntity();
        row.id = required.id().value();
        row.organizationId = required.scope().organizationId().value();
        row.teamId = required.scope().teamId().value();
        row.workspaceId = required.scope().workspaceId().value();
        row.projectId = required.workProjectId().value();
        row.conversationId = required.conversationId().value();
        row.workItemId = required.workItemId().value();
        row.taskId = required.taskId().value();
        row.origin = required.origin().name();
        putAudit(row, required.audit(), 0);
        return row;
    }

    ConversationTaskLink toLinkDomain(ConversationTaskLinkEntity row) {
        return ConversationTaskLink.reconstitute(
                new ConversationTaskLinkId(row.id),
                new io.crewscope.domain.conversation.ConversationScope(
                        new OrganizationId(row.organizationId),
                        new TeamId(row.teamId),
                        new WorkspaceId(row.workspaceId)),
                new ConversationId(row.conversationId),
                new WorkProjectId(row.projectId),
                new WorkItemId(row.workItemId),
                new TaskId(row.taskId),
                ConversationTaskLinkOrigin.valueOf(row.origin),
                new PrincipalId(row.createdByPrincipalId),
                audit(row));
    }

    TaskExecutionEntity toExecutionEntity(TaskExecution execution) {
        TaskExecution required = Objects.requireNonNull(execution, "execution");
        TaskExecutionEntity row = new TaskExecutionEntity();
        row.id = required.id().value();
        putScope(row, required.scope());
        row.taskId = required.taskId().value();
        row.attempt = required.attempt();
        row.maxAttempts = required.maxAttempts();
        row.parentExecutionId = required.parentExecutionId().map(TaskExecutionId::value).orElse(null);
        row.priority = required.priority().value();
        row.notBefore = required.notBefore().value();
        copyExecutionState(row, required);
        putAudit(row, required.audit(), required.version());
        return row;
    }

    void copyExecutionState(TaskExecutionEntity row, TaskExecution execution) {
        row.priority = execution.priority().value();
        row.notBefore = execution.notBefore().value();
        row.status = execution.status().name();
        TaskExecutionWaiting waiting = execution.waiting().orElse(null);
        row.waitingReason = waiting == null ? null : waiting.reason().name();
        row.waitingSince = waiting == null ? null : waiting.waitingSince().value();
        TaskExecutionControlRequest request = execution.controlRequest().orElse(null);
        row.controlRequestType = request == null ? null : request.type().name();
        row.controlRequestedByPrincipalId = request == null
                ? null : request.requestedByPrincipalId().value();
        row.controlRequestedAt = request == null ? null : request.requestedAt().value();
        row.controlRequestReason = request == null ? null : request.reason();
        TaskExecutionTerminal terminal = execution.terminal().orElse(null);
        row.terminalDecidedByPrincipalId = terminal == null
                ? null : terminal.decidedByPrincipalId().value();
        row.terminalDecidedAt = terminal == null ? null : terminal.decidedAt().value();
        TaskExecutionFailure terminalFailure = terminal == null
                ? null : terminal.failure().orElse(null);
        row.terminalFailureClass = terminalFailure == null
                ? null : terminalFailure.failureClass().name();
        row.terminalFailureCode = terminalFailure == null ? null : terminalFailure.code();
        TaskExecutionPlanningContext planning = execution.planningContext().orElse(null);
        row.executionPrincipalId = planning == null
                ? null : planning.executionPrincipal().principalId().value();
        row.executionAssignmentId = planning == null
                ? null : planning.executionPrincipal().assignmentId().value();
        row.executionAssignmentVersion = planning == null
                ? null : planning.executionPrincipal().assignmentVersion();
        row.responsibilitySnapshotHash = planning == null
                ? null : planning.executionPrincipal().responsibilitySnapshotHash().value();
        row.currentPolicySnapshotId = planning == null ? null : planning.policySnapshotId().value();
        row.currentPolicySnapshotHash = planning == null ? null : planning.policySnapshotHash().value();
        row.currentSafetyOverlayId = planning == null ? null : planning.safetyOverlay().id().value();
        row.currentSafetyOverlayVersion = planning == null ? null : planning.safetyOverlay().version();
        row.currentSafetyOverlayHash = planning == null ? null : planning.safetyOverlay().overlayHash().value();
        row.currentPlanVersionId = planning == null
                ? null : planning.currentPlanVersionId().map(PlanVersionId::value).orElse(null);
        row.currentPlanVersionHash = planning == null
                ? null : planning.currentPlanVersionHash().map(TaskFactHash::value).orElse(null);
        row.lastFencingToken = execution.lastFencingToken().map(FencingToken::value).orElse(null);
        row.updatedAt = execution.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(execution.audit().updatedBy(), "taskExecution.updatedBy");
    }

    TaskExecution toExecutionDomain(TaskExecutionEntity row) {
        Optional<TaskExecutionWaiting> waiting = row.waitingReason == null
                ? Optional.empty()
                : Optional.of(new TaskExecutionWaiting(
                        TaskExecutionWaitReason.valueOf(row.waitingReason),
                        new UtcTimestamp(row.waitingSince)));
        Optional<TaskExecutionControlRequest> request = row.controlRequestType == null
                ? Optional.empty()
                : Optional.of(new TaskExecutionControlRequest(
                        TaskExecutionControlRequestType.valueOf(row.controlRequestType),
                        new PrincipalId(row.controlRequestedByPrincipalId),
                        new UtcTimestamp(row.controlRequestedAt),
                        row.controlRequestReason));
        Optional<TaskExecutionTerminal> terminal = row.terminalDecidedAt == null
                ? Optional.empty()
                : Optional.of(new TaskExecutionTerminal(
                        TaskExecutionStatus.valueOf(row.status),
                        new PrincipalId(row.terminalDecidedByPrincipalId),
                        new UtcTimestamp(row.terminalDecidedAt),
                        row.terminalFailureClass == null
                                ? Optional.empty()
                                : Optional.of(new TaskExecutionFailure(
                                        TaskExecutionFailureClass.valueOf(row.terminalFailureClass),
                                        row.terminalFailureCode))));
        Optional<TaskExecutionPlanningContext> planning = row.executionPrincipalId == null
                ? Optional.empty()
                : Optional.of(new TaskExecutionPlanningContext(
                        executionPrincipal(
                                row.executionPrincipalId,
                                row.executionAssignmentId,
                                row.executionAssignmentVersion,
                                row.responsibilitySnapshotHash),
                        new io.crewscope.domain.task.PolicySnapshotId(row.currentPolicySnapshotId),
                        new TaskFactHash(row.currentPolicySnapshotHash),
                        new SafetyEnforcementOverlayReference(
                                new SafetyEnforcementOverlayId(row.currentSafetyOverlayId),
                                row.currentSafetyOverlayVersion,
                                new TaskFactHash(row.currentSafetyOverlayHash)),
                        optionalId(row.currentPlanVersionId, PlanVersionId::new),
                        optional(row.currentPlanVersionHash, TaskFactHash::new)));
        return TaskExecution.reconstitute(
                new TaskExecutionId(row.id),
                scope(row),
                new TaskId(row.taskId),
                row.attempt,
                row.maxAttempts,
                optionalId(row.parentExecutionId, TaskExecutionId::new),
                new TaskExecutionPriority(row.priority),
                new UtcTimestamp(row.notBefore),
                TaskExecutionStatus.valueOf(row.status),
                waiting,
                request,
                terminal,
                planning,
                optional(row.lastFencingToken, FencingToken::new),
                row.version,
                audit(row));
    }

    ExecutionRuntimeEntity toRuntimeEntity(ExecutionRuntime runtime) {
        ExecutionRuntime required = Objects.requireNonNull(runtime, "runtime");
        ExecutionRuntimeEntity row = new ExecutionRuntimeEntity();
        row.id = required.id().value();
        row.organizationId = required.organizationId().value();
        row.runtimeEnvironment = required.environment().value();
        row.runtimeKey = required.key();
        copyRuntimeState(row, required);
        putAudit(row, required.audit(), required.version());
        return row;
    }

    void copyRuntimeState(ExecutionRuntimeEntity row, ExecutionRuntime runtime) {
        row.displayName = runtime.displayName();
        row.implementationVersion = runtime.implementationVersion();
        putCapabilities(row, runtime.capabilities());
        row.status = runtime.status().name();
        row.updatedAt = runtime.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(runtime.audit().updatedBy(), "runtime.updatedBy");
    }

    ExecutionRuntime toRuntimeDomain(ExecutionRuntimeEntity row) {
        return ExecutionRuntime.reconstitute(
                new ExecutionRuntimeId(row.id),
                new OrganizationId(row.organizationId),
                new RuntimeEnvironment(row.runtimeEnvironment),
                row.runtimeKey,
                row.displayName,
                row.implementationVersion,
                capabilities(row.capabilities, row.languages, row.buildSystems),
                ExecutionRuntimeStatus.valueOf(row.status),
                row.version,
                audit(row));
    }

    RuntimeWorkerEntity toWorkerEntity(RuntimeWorker worker) {
        RuntimeWorker required = Objects.requireNonNull(worker, "worker");
        RuntimeWorkerEntity row = new RuntimeWorkerEntity();
        row.id = required.id().value();
        row.organizationId = required.organizationId().value();
        row.runtimeEnvironment = required.environment().value();
        row.runtimeId = required.runtimeId().value();
        row.stableKey = required.stableKey();
        copyWorkerState(row, required);
        putAudit(row, required.audit(), required.version());
        return row;
    }

    void copyWorkerState(RuntimeWorkerEntity row, RuntimeWorker worker) {
        row.runtimeProfile = worker.profile().name();
        putCapabilities(row, worker.capabilities());
        row.maxConcurrentExecutions = worker.capacity().maxConcurrentExecutions();
        row.activeExecutions = worker.capacity().activeExecutions();
        row.status = worker.status().name();
        row.lastHeartbeatAt = worker.lastHeartbeatAt().value();
        row.heartbeatSequence = worker.heartbeatSequence();
        row.updatedAt = worker.audit().updatedAt().value();
        row.updatedByPrincipalId = principal(worker.audit().updatedBy(), "worker.updatedBy");
    }

    RuntimeWorker toWorkerDomain(RuntimeWorkerEntity row) {
        return RuntimeWorker.reconstitute(
                new RuntimeWorkerId(row.id),
                new OrganizationId(row.organizationId),
                new RuntimeEnvironment(row.runtimeEnvironment),
                new ExecutionRuntimeId(row.runtimeId),
                row.stableKey,
                RuntimeProfile.valueOf(row.runtimeProfile),
                capabilities(row.capabilities, row.languages, row.buildSystems),
                new RuntimeWorkerCapacity(row.maxConcurrentExecutions, row.activeExecutions),
                RuntimeWorkerStatus.valueOf(row.status),
                new UtcTimestamp(row.lastHeartbeatAt),
                row.heartbeatSequence,
                row.version,
                audit(row));
    }

    static UUID snapshotId(TaskId taskId) {
        return UUID.nameUUIDFromBytes(
                ("crewscope:task-responsibility:" + taskId.value())
                        .getBytes(StandardCharsets.UTF_8));
    }

    static TaskFactHash responsibilityHash(TaskResponsibilitySnapshot snapshot) {
        StringBuilder canonical = new StringBuilder()
                .append(snapshot.scope().organizationId()).append('|')
                .append(snapshot.scope().teamId()).append('|')
                .append(snapshot.scope().workspaceId()).append('|')
                .append(snapshot.scope().projectId()).append('|')
                .append(snapshot.workItemId()).append('|')
                .append(snapshot.capturedAt());
        snapshot.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.assignmentId().toString()))
                .forEach(entry -> canonical.append('|')
                        .append(entry.assignmentId()).append(':')
                        .append(entry.assignmentVersion()).append(':')
                        .append(entry.role()).append(':')
                        .append(entry.principalId()).append(':')
                        .append(entry.principalType()).append(':')
                        .append(entry.memberId().map(Object::toString).orElse("-")));
        return TaskFactHash.sha256(canonical.toString());
    }

    TaskResponsibilitySnapshotEntry responsibilityEntry(Object[] values) {
        return new TaskResponsibilitySnapshotEntry(
                new ResponsibilityAssignmentId((UUID) values[0]),
                ((Number) values[1]).longValue(),
                ResponsibilityRole.valueOf((String) values[2]),
                new PrincipalId((UUID) values[3]),
                PrincipalType.valueOf((String) values[4]),
                optionalId((UUID) values[5], TeamMemberId::new),
                new UtcTimestamp(instant(values[6])),
                new UtcTimestamp(instant(values[7])));
    }

    private static ExecutionPrincipalSnapshot executionPrincipal(
            UUID principalId, UUID assignmentId, long assignmentVersion, String hash) {
        return new ExecutionPrincipalSnapshot(
                new PrincipalId(principalId),
                new ResponsibilityAssignmentId(assignmentId),
                assignmentVersion,
                new TaskFactHash(hash));
    }

    private static RuntimeCapabilities capabilities(
            List<String> values, List<String> languages, List<String> buildSystems) {
        Set<RuntimeCapability> capabilities = values.stream()
                .map(RuntimeCapability::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new RuntimeCapabilities(
                capabilities, Set.copyOf(languages), Set.copyOf(buildSystems));
    }

    private static void putCapabilities(ExecutionRuntimeEntity row, RuntimeCapabilities capabilities) {
        row.capabilities = capabilities.values().stream().map(Enum::name).sorted().toList();
        row.languages = capabilities.languages().stream().sorted().toList();
        row.buildSystems = capabilities.buildSystems().stream().sorted().toList();
    }

    private static void putCapabilities(RuntimeWorkerEntity row, RuntimeCapabilities capabilities) {
        row.capabilities = capabilities.values().stream().map(Enum::name).sorted().toList();
        row.languages = capabilities.languages().stream().sorted().toList();
        row.buildSystems = capabilities.buildSystems().stream().sorted().toList();
    }

    private static WorkItemScope scope(WorkScopedRow row) {
        return new WorkItemScope(
                new OrganizationId(row.organizationId),
                new TeamId(row.teamId),
                new WorkspaceId(row.workspaceId),
                new WorkProjectId(row.projectId));
    }

    private static void putScope(WorkScopedRow row, WorkItemScope scope) {
        row.organizationId = scope.organizationId().value();
        row.teamId = scope.teamId().value();
        row.workspaceId = scope.workspaceId().value();
        row.projectId = scope.projectId().value();
    }

    private static void putAudit(
            AuditedVersionedRow row, AuditMetadata audit, long version) {
        row.version = version;
        row.createdAt = audit.createdAt().value();
        row.createdByPrincipalId = principal(audit.createdBy(), "createdBy");
        row.updatedAt = audit.updatedAt().value();
        row.updatedByPrincipalId = principal(audit.updatedBy(), "updatedBy");
    }

    private static AuditMetadata audit(AuditedVersionedRow row) {
        return new AuditMetadata(
                Optional.of(new PrincipalId(row.createdByPrincipalId)),
                new UtcTimestamp(row.createdAt),
                Optional.of(new PrincipalId(row.updatedByPrincipalId)),
                new UtcTimestamp(row.updatedAt));
    }

    private static UUID principal(Optional<PrincipalId> value, String field) {
        return Objects.requireNonNull(value, field)
                .orElseThrow(() -> new IllegalArgumentException(field + " is required"))
                .value();
    }

    private static <T> Optional<T> optionalId(UUID value, java.util.function.Function<UUID, T> factory) {
        return value == null ? Optional.empty() : Optional.of(factory.apply(value));
    }

    private static <S, T> Optional<T> optional(S value, java.util.function.Function<S, T> factory) {
        return value == null ? Optional.empty() : Optional.of(factory.apply(value));
    }

    private static java.time.Instant instant(Object value) {
        if (value instanceof java.time.Instant instant) {
            return instant;
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalArgumentException("Unsupported timestamp value: " + value);
    }
}
