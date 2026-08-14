package io.crewscope.domain.task;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable AgentScope state binding for one Task execution or one of its Steps.
 *
 * <p>The existing Conversation {@code AgentRuntimeSession} remains the Personal Agent binding.
 * This aggregate adds the Task-side TASK, STEP and SPECIALIST shapes without weakening the M2
 * Conversation invariants. D08 persists both shapes in the expanded runtime-session table.
 */
public final class TaskAgentRuntimeSession {

    private final AgentRuntimeSessionId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final Optional<StepExecutionId> stepExecutionId;
    private final TaskAgentSessionPurpose purpose;
    private final PrincipalId agentPrincipalId;
    private final AgentProfileId agentProfileId;
    private final long agentProfileVersion;
    private final AgentScopeSessionKey agentScopeKey;
    private final AgentRuntimeStateReference stateReference;
    private final AgentRuntimeSessionStatus status;
    private final long version;
    private final AuditMetadata audit;

    private TaskAgentRuntimeSession(
            AgentRuntimeSessionId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            TaskAgentSessionPurpose purpose,
            PrincipalId agentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            AgentScopeSessionKey agentScopeKey,
            AgentRuntimeStateReference stateReference,
            AgentRuntimeSessionStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.stepExecutionId = requireStepShape(purpose, stepExecutionId);
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        this.agentProfileVersion = requireNonNegative(
                agentProfileVersion, "taskAgentRuntimeSession.agentProfileVersion");
        requireDerivedId();
        this.agentScopeKey = requireDerivedKey(agentScopeKey);
        this.stateReference = requireStateReference(stateReference);
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireNonNegative(version, "taskAgentRuntimeSession.version");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates a Task Orchestrator state slot bound to the exact execution attempt. */
    public static TaskAgentRuntimeSession initializeTask(
            Task task,
            TaskExecution execution,
            AgentProfile profile,
            Principal agent,
            UtcTimestamp occurredAt) {
        return initialize(
                task,
                execution,
                Optional.empty(),
                TaskAgentSessionPurpose.TASK,
                profile,
                agent,
                occurredAt);
    }

    /** Creates a Team Agent state slot for one serial Step. */
    public static TaskAgentRuntimeSession initializeStep(
            Task task,
            TaskExecution execution,
            StepExecution step,
            AgentProfile profile,
            Principal agent,
            UtcTimestamp occurredAt) {
        return initialize(
                task,
                execution,
                Optional.of(Objects.requireNonNull(step, "step")),
                TaskAgentSessionPurpose.STEP,
                profile,
                agent,
                occurredAt);
    }

    /** Creates a Specialist Agent state slot for one serial Step. */
    public static TaskAgentRuntimeSession initializeSpecialist(
            Task task,
            TaskExecution execution,
            StepExecution step,
            AgentProfile profile,
            Principal specialist,
            UtcTimestamp occurredAt) {
        return initialize(
                task,
                execution,
                Optional.of(Objects.requireNonNull(step, "step")),
                TaskAgentSessionPurpose.SPECIALIST,
                profile,
                specialist,
                occurredAt);
    }

    /** Reconstitutes persisted Task-side session metadata and rechecks its derived coordinates. */
    public static TaskAgentRuntimeSession reconstitute(
            AgentRuntimeSessionId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<StepExecutionId> stepExecutionId,
            TaskAgentSessionPurpose purpose,
            PrincipalId agentPrincipalId,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            AgentScopeSessionKey agentScopeKey,
            AgentRuntimeStateReference stateReference,
            AgentRuntimeSessionStatus status,
            long version,
            AuditMetadata audit) {
        return new TaskAgentRuntimeSession(
                id,
                scope,
                taskId,
                executionId,
                stepExecutionId,
                purpose,
                agentPrincipalId,
                agentProfileId,
                agentProfileVersion,
                agentScopeKey,
                stateReference,
                status,
                version,
                audit);
    }

    /** Disables new calls while preserving the external AgentState identity. */
    public TaskAgentRuntimeSession disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureTransition(AgentRuntimeSessionStatus.ACTIVE, AgentRuntimeSessionStatus.DISABLED);
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "taskAgentRuntimeSession.updatedBy");
        return copy(
                agentProfileVersion,
                AgentRuntimeSessionStatus.DISABLED,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Reactivates the same binding after the current profile and Principal are revalidated. */
    public TaskAgentRuntimeSession activate(
            AgentProfile profile,
            Principal agent,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureTransition(AgentRuntimeSessionStatus.DISABLED, AgentRuntimeSessionStatus.ACTIVE);
        requireProfile(profile, agent, scope, purpose, agentProfileId);
        requirePinnedAgent(agent);
        if (profile.version() < agentProfileVersion) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.agentProfileVersion",
                    "must not move the pinned AgentProfile version backwards");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "taskAgentRuntimeSession.updatedBy");
        return copy(
                profile.version(),
                AgentRuntimeSessionStatus.ACTIVE,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Advances the pinned profile version without changing the state slot or execution scope. */
    public TaskAgentRuntimeSession refreshConfiguration(
            AgentProfile profile,
            Principal agent,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        ensureTransition(AgentRuntimeSessionStatus.ACTIVE, AgentRuntimeSessionStatus.ACTIVE);
        requireProfile(profile, agent, scope, purpose, agentProfileId);
        requirePinnedAgent(agent);
        if (profile.version() <= agentProfileVersion) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.agentProfileVersion",
                    "must advance to a newer active AgentProfile version");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "taskAgentRuntimeSession.updatedBy");
        return copy(
                profile.version(),
                AgentRuntimeSessionStatus.ACTIVE,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    public boolean canInvoke() {
        return status == AgentRuntimeSessionStatus.ACTIVE;
    }

    public AgentRuntimeSessionId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskId taskId() {
        return taskId;
    }

    public TaskExecutionId executionId() {
        return executionId;
    }

    public Optional<StepExecutionId> stepExecutionId() {
        return stepExecutionId;
    }

    public TaskAgentSessionPurpose purpose() {
        return purpose;
    }

    public PrincipalId agentPrincipalId() {
        return agentPrincipalId;
    }

    public AgentProfileId agentProfileId() {
        return agentProfileId;
    }

    public long agentProfileVersion() {
        return agentProfileVersion;
    }

    public AgentScopeSessionKey agentScopeKey() {
        return agentScopeKey;
    }

    public AgentRuntimeStateReference stateReference() {
        return stateReference;
    }

    public AgentRuntimeSessionStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static TaskAgentRuntimeSession initialize(
            Task task,
            TaskExecution execution,
            Optional<StepExecution> step,
            TaskAgentSessionPurpose purpose,
            AgentProfile profile,
            Principal agent,
            UtcTimestamp occurredAt) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        if (requiredTask.isClosed()
                || requiredExecution.status().isTerminal()
                || !requiredTask.scope().equals(requiredExecution.scope())
                || !requiredTask.id().equals(requiredExecution.taskId())) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.executionId",
                    "must reference an open Task and its non-terminal execution attempt");
        }
        Optional<StepExecutionId> stepId = Objects.requireNonNull(step, "step")
                .map(candidate -> requireStep(candidate, requiredTask, requiredExecution, agent));
        requireStepShape(purpose, stepId);
        AgentProfile requiredProfile = requireProfile(
                profile, agent, requiredTask.scope(), purpose, null);
        AgentRuntimeSessionId id = AgentRuntimeSessionId.forTaskExecution(
                requiredExecution.id(), stepId, requiredProfile.id(), purpose.name());
        AgentScopeSessionKey key = AgentScopeSessionKey.forTaskExecution(
                requiredTask.scope().organizationId(), agent.id(), requiredExecution.id(), id);
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                agent, requiredTask.scope(), "taskAgentRuntimeSession.createdBy");
        return new TaskAgentRuntimeSession(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                requiredExecution.id(),
                stepId,
                purpose,
                agent.id(),
                requiredProfile.id(),
                requiredProfile.version(),
                key,
                AgentRuntimeStateReference.forSession(id),
                AgentRuntimeSessionStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    private static StepExecutionId requireStep(
            StepExecution step, Task task, TaskExecution execution, Principal agent) {
        if (!step.scope().equals(task.scope())
                || !step.taskId().equals(task.id())
                || !step.executionId().equals(execution.id())
                || step.status().isTerminal()
                || !step.executionPrincipal().principalId().equals(agent.id())) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.stepExecutionId",
                    "must reference a non-terminal Step assigned to this Agent");
        }
        return step.id();
    }

    private static AgentProfile requireProfile(
            AgentProfile profile,
            Principal agent,
            WorkItemScope scope,
            TaskAgentSessionPurpose purpose,
            AgentProfileId expectedId) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        Principal requiredAgent = Objects.requireNonNull(agent, "agent");
        AgentProfileType expectedProfileType = purpose == TaskAgentSessionPurpose.SPECIALIST
                ? AgentProfileType.SPECIALIST
                : AgentProfileType.TEAM;
        PrincipalType expectedPrincipalType = purpose == TaskAgentSessionPurpose.SPECIALIST
                ? PrincipalType.SPECIALIST_AGENT
                : PrincipalType.TEAM_AGENT;
        boolean wrongTeam = requiredProfile.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        boolean agentOutsideTeam = requiredAgent.scope().teamId().isPresent()
                && requiredAgent.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (requiredProfile.status() != AgentProfileStatus.ACTIVE
                || requiredProfile.type() != expectedProfileType
                || !requiredProfile.scope().organizationId().equals(scope.organizationId())
                || wrongTeam
                || !requiredProfile.workspaceId().equals(scope.workspaceId())
                || !requiredProfile.agentPrincipalId().equals(requiredAgent.id())
                || (expectedId != null && !requiredProfile.id().equals(expectedId))
                || !requiredAgent.canAct()
                || requiredAgent.type() != expectedPrincipalType
                || !requiredAgent.scope().organizationId().equals(scope.organizationId())
                || agentOutsideTeam) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.agentProfileId",
                    "must bind the active in-scope Agent and matching AgentProfile type");
        }
        return requiredProfile;
    }

    private TaskAgentRuntimeSession copy(
            long targetProfileVersion,
            AgentRuntimeSessionStatus targetStatus,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new TaskAgentRuntimeSession(
                id,
                scope,
                taskId,
                executionId,
                stepExecutionId,
                purpose,
                agentPrincipalId,
                agentProfileId,
                targetProfileVersion,
                agentScopeKey,
                stateReference,
                targetStatus,
                targetVersion,
                targetAudit);
    }

    private void requireDerivedId() {
        AgentRuntimeSessionId expected = AgentRuntimeSessionId.forTaskExecution(
                executionId, stepExecutionId, agentProfileId, purpose.name());
        if (!id.equals(expected)) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.id", "must be derived from the complete Task binding");
        }
    }

    private AgentScopeSessionKey requireDerivedKey(AgentScopeSessionKey value) {
        AgentScopeSessionKey expected = AgentScopeSessionKey.forTaskExecution(
                scope.organizationId(), agentPrincipalId, executionId, id);
        if (!expected.equals(value)) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.agentScopeKey",
                    "must be derived from the trusted Task binding");
        }
        return value;
    }

    private AgentRuntimeStateReference requireStateReference(AgentRuntimeStateReference value) {
        AgentRuntimeStateReference required = Objects.requireNonNull(value, "stateReference");
        if (!required.belongsTo(id)) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.stateReference", "must belong to this session");
        }
        return required;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "TaskAgentRuntimeSession", id, expectedVersion, version);
        }
    }

    private void requirePinnedAgent(Principal agent) {
        if (!Objects.requireNonNull(agent, "agent").id().equals(agentPrincipalId)) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.agentPrincipalId",
                    "must preserve the pinned Agent Principal");
        }
    }

    private void ensureTransition(
            AgentRuntimeSessionStatus required, AgentRuntimeSessionStatus target) {
        if (status != required) {
            throw new InvalidStateTransitionException(
                    "TaskAgentRuntimeSession", id, status, target);
        }
    }

    private static Optional<StepExecutionId> requireStepShape(
            TaskAgentSessionPurpose purpose, Optional<StepExecutionId> stepExecutionId) {
        TaskAgentSessionPurpose requiredPurpose = Objects.requireNonNull(purpose, "purpose");
        Optional<StepExecutionId> requiredStep = Objects.requireNonNull(
                stepExecutionId, "stepExecutionId");
        if ((requiredPurpose == TaskAgentSessionPurpose.TASK) == requiredStep.isPresent()) {
            throw new DomainValidationException(
                    "taskAgentRuntimeSession.stepExecutionId",
                    "must be absent for TASK and present for STEP or SPECIALIST");
        }
        return requiredStep;
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new DomainValidationException(field, "must not be negative");
        }
        return value;
    }
}
