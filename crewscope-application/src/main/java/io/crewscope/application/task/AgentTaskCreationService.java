package io.crewscope.application.task;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.conversation.ReadableConversationMessage;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ConversationTaskLink;
import io.crewscope.domain.task.ConversationTaskLinkOrigin;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskResponsibilitySnapshot;
import io.crewscope.domain.task.TaskSource;
import io.crewscope.domain.task.event.TaskDelegatedToAgent;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomically delegates one current WorkItem to an assigned Agent and publishes its first attempt. */
public final class AgentTaskCreationService {

    private static final String COMMAND_TYPE = "DELEGATE_WORK_ITEM_TO_AGENT";
    private static final String TASK_AGGREGATE = "TASK";

    private final WorkItemAccessPolicy accessPolicy;
    private final WorkItemRepository workItemRepository;
    private final ResponsibilityAssignmentRepository assignmentRepository;
    private final PrincipalRepository principalRepository;
    private final AgentProfileRepository profileRepository;
    private final ConversationApplicationService conversationService;
    private final ProviderBindingResolver bindingResolver;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final PolicySnapshotRepository policyRepository;
    private final SafetyEnforcementOverlayRepository overlayRepository;
    private final ConversationTaskLinkRepository conversationTaskLinkRepository;
    private final DomainEventStore eventStore;
    private final ConversationEventRepository conversationEventRepository;
    private final TaskEventRepository taskEventRepository;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore receiptStore;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;
    private final TaskCreationPolicySpec creationPolicy;

    public AgentTaskCreationService(
            WorkItemAccessPolicy accessPolicy,
            WorkItemRepository workItemRepository,
            ResponsibilityAssignmentRepository assignmentRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            ConversationApplicationService conversationService,
            ProviderBindingResolver bindingResolver,
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            ConversationTaskLinkRepository conversationTaskLinkRepository,
            DomainEventStore eventStore,
            ConversationEventRepository conversationEventRepository,
            TaskEventRepository taskEventRepository,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider,
            TaskCreationPolicySpec creationPolicy) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
        this.assignmentRepository = Objects.requireNonNull(
                assignmentRepository, "assignmentRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
        this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.overlayRepository = Objects.requireNonNull(overlayRepository, "overlayRepository");
        this.conversationTaskLinkRepository = Objects.requireNonNull(
                conversationTaskLinkRepository, "conversationTaskLinkRepository");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.conversationEventRepository = Objects.requireNonNull(
                conversationEventRepository, "conversationEventRepository");
        this.taskEventRepository = Objects.requireNonNull(
                taskEventRepository, "taskEventRepository");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.creationPolicy = Objects.requireNonNull(creationPolicy, "creationPolicy");
    }

    public CommandExecution<AgentTaskCreationResult> create(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            CreateAgentTaskCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        WorkProjectId project = Objects.requireNonNull(projectId, "projectId");
        WorkItemId workItem = Objects.requireNonNull(workItemId, "workItemId");
        CreateAgentTaskCommand required = Objects.requireNonNull(command, "command");
        CommandRequestHash requestHash = requestHash(trusted, team, project, workItem, required);
        return transactionExecutor.required(() -> createInTransaction(
                trusted, team, project, workItem, required, requestHash));
    }

    private CommandExecution<AgentTaskCreationResult> createInTransaction(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            CreateAgentTaskCommand command,
            CommandRequestHash requestHash) {
        Principal actor = context.access().actor();
        OrganizationId organizationId = actor.scope().organizationId();
        UtcTimestamp occurredAt = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receiptStore.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                COMMAND_TYPE,
                requestHash,
                commandId,
                context.correlationId(),
                occurredAt));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }

        // Visibility is checked before locking; the complete WorkItem and responsibility chain are
        // then reloaded while the WorkItem row lock serializes responsibility-sensitive creation.
        accessPolicy.requireVisibleWorkItem(
                context.access(), organizationId, teamId, projectId, workItemId);
        assignmentRepository.lockResponsibilityChain(organizationId, workItemId);
        WorkItem workItem = requireLockedWorkItem(
                organizationId, teamId, projectId, workItemId, command.expectedWorkItemVersion());
        List<ResponsibilityAssignment> assignments = List.copyOf(
                assignmentRepository.findActiveByWorkItem(organizationId, workItemId));
        requireDelegationAuthority(actor, assignments);
        TaskResponsibilitySnapshot responsibilitySnapshot = TaskResponsibilitySnapshot.capture(
                workItem, assignments, occurredAt);

        AgentProfile profile = requireProfile(organizationId, workItem, command);
        Principal executor = requireExecutor(organizationId, workItem, profile, assignments);
        Optional<ReadableConversationMessage> sourceMessage = command.conversationSource()
                .map(source -> conversationService.requireReadableMessage(
                        context.access(),
                        organizationId,
                        teamId,
                        source.conversationId(),
                        source.messageId()));
        TaskSource source = sourceMessage
                .map(value -> TaskSource.fromMessage(
                        workItem, value.conversation(), value.message()))
                .orElseGet(() -> TaskSource.fromWorkItem(workItem));
        Set<ProviderBindingId> providerBindingIds = requireCurrentBindings(
                organizationId, workItem, executor, command.providerBindingIds());

        Task createdTask = taskRepository.create(Task.create(
                TaskId.generate(),
                workItem,
                source,
                command.brief(),
                responsibilitySnapshot,
                actor,
                occurredAt));
        TaskExecution createdExecution = executionRepository.create(TaskExecution.firstAttempt(
                TaskExecutionId.generate(),
                createdTask,
                creationPolicy.maxAttempts(),
                creationPolicy.priority(),
                occurredAt,
                actor,
                occurredAt));
        PolicySnapshot policy = policyRepository.create(PolicySnapshot.initial(
                PolicySnapshotId.generate(),
                createdTask,
                createdExecution,
                executor,
                creationPolicy.policyPack(),
                profile.id(),
                profile.version(),
                creationPolicy.capabilities(),
                creationPolicy.allowedTools(),
                providerBindingIds,
                creationPolicy.budget(),
                actor,
                occurredAt));
        SafetyEnforcementOverlay overlay = overlayRepository.create(
                SafetyEnforcementOverlay.unrestricted(
                        SafetyEnforcementOverlayId.generate(),
                        createdTask,
                        createdExecution,
                        actor,
                        occurredAt));
        TaskExecution planned = executionRepository.update(
                createdExecution.initializePlanningContext(
                        policy, overlay, createdExecution.version(), actor, occurredAt));
        TaskExecution ready = executionRepository.update(
                planned.markReady(planned.version(), actor, occurredAt));
        Task activeTask = taskRepository.update(createdTask.switchCurrentExecution(
                Optional.empty(), ready.id(), createdTask.version(), actor, occurredAt));

        sourceMessage.ifPresent(value -> conversationTaskLinkRepository.create(
                ConversationTaskLink.link(
                        value.conversation(),
                        activeTask,
                        ConversationTaskLinkOrigin.SOURCE,
                        actor,
                        occurredAt)));
        AgentTaskCreationResult result = new AgentTaskCreationResult(
                activeTask, ready, policy, overlay);
        return completed(context, commandId, result, profile, sourceMessage, occurredAt);
    }

    private WorkItem requireLockedWorkItem(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            long expectedVersion) {
        WorkItem workItem = workItemRepository.findById(organizationId, workItemId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .filter(value -> value.scope().projectId().equals(projectId))
                .orElseThrow(() -> new AggregateNotFoundException("WorkItem", workItemId));
        if (workItem.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "WorkItem", workItemId, expectedVersion, workItem.version());
        }
        return workItem;
    }

    private AgentProfile requireProfile(
            OrganizationId organizationId, WorkItem workItem, CreateAgentTaskCommand command) {
        AgentProfile profile = profileRepository.findById(
                        organizationId, command.executorAgentProfileId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", command.executorAgentProfileId()));
        boolean current = profile.status() == AgentProfileStatus.ACTIVE
                && profile.scope().organizationId().equals(organizationId)
                && profile.scope().teamId().filter(workItem.scope().teamId()::equals).isPresent()
                && profile.workspaceId().equals(workItem.scope().workspaceId());
        if (!current) {
            throw new DomainValidationException(
                    "agentTask.agentProfileId", "must reference a current Agent in the WorkItem Workspace");
        }
        return profile;
    }

    private Principal requireExecutor(
            OrganizationId organizationId,
            WorkItem workItem,
            AgentProfile profile,
            List<ResponsibilityAssignment> assignments) {
        Principal executor = principalRepository.findById(
                        organizationId, profile.agentPrincipalId())
                .filter(Principal::canAct)
                .filter(value -> isTaskOrchestrator(profile.type(), value.type()))
                .orElseThrow(() -> new DomainValidationException(
                        "agentTask.executorPrincipalId",
                        "must reference the active Personal or Team Agent Profile Principal"));
        boolean assigned = assignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.EXECUTOR)
                .anyMatch(value -> value.actorPrincipalId().equals(executor.id())
                        && value.actorType() == executor.type()
                        && value.scope().equals(workItem.scope()));
        if (!assigned) {
            throw new DomainValidationException(
                    "agentTask.executorPrincipalId",
                    "must reference a current Executor responsibility for this WorkItem");
        }
        return executor;
    }

    private static boolean isTaskOrchestrator(
            AgentProfileType profileType, PrincipalType principalType) {
        return (profileType == AgentProfileType.PERSONAL
                        && principalType == PrincipalType.PERSONAL_AGENT)
                || (profileType == AgentProfileType.TEAM
                        && principalType == PrincipalType.TEAM_AGENT);
    }

    private Set<ProviderBindingId> requireCurrentBindings(
            OrganizationId organizationId,
            WorkItem workItem,
            Principal executor,
            Set<ProviderBindingId> requestedIds) {
        for (ProviderBindingId id : requestedIds) {
            ProviderBindingCandidate candidate = bindingResolver.resolveCurrent(organizationId, id)
                    .orElseThrow(() -> new DomainValidationException(
                            "agentTask.providerBindingIds",
                            "must contain only current ProviderBinding facts"));
            ProviderBinding binding = candidate.binding();
            boolean targetMatches = binding.target().teamId().equals(workItem.scope().teamId())
                    && binding.target().workspaceId().equals(workItem.scope().workspaceId())
                    && (binding.target().type() == ProviderBindingTargetType.WORKSPACE
                            || binding.target().workProjectId()
                                    .filter(workItem.scope().projectId()::equals)
                                    .isPresent());
            boolean userOwnerMatches = binding.owner().type() != ProviderOwnerType.USER
                    || executor.ownerPrincipalId()
                            .equals(binding.owner().userPrincipalId());
            if (!targetMatches || !userOwnerMatches) {
                throw new PolicyDeniedException("use this Provider Binding for the Task Agent");
            }
        }
        return Set.copyOf(requestedIds);
    }

    private CommandExecution<AgentTaskCreationResult> completed(
            TeamCommandContext context,
            UUID commandId,
            AgentTaskCreationResult result,
            AgentProfile profile,
            Optional<ReadableConversationMessage> sourceMessage,
            UtcTimestamp occurredAt) {
        Task task = result.task();
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<TaskDelegatedToAgent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("TASK_DELEGATED_TO_AGENT"),
                SchemaVersion.V1,
                task.scope().organizationId(),
                Optional.of(task.scope().teamId()),
                Optional.of(task.scope().workspaceId()),
                AggregateReference.of(TASK_AGGREGATE, task.id()),
                task.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                occurredAt,
                TaskDelegatedToAgent.from(
                        task,
                        result.execution(),
                        result.policySnapshot(),
                        result.safetyOverlay(),
                        profile));
        eventStore.append(event);
        taskEventRepository.append(
                TaskEventContext.execution(task.id(), result.execution().id()), event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        sourceMessage.ifPresent(value -> conversationEventRepository.append(
                value.conversation().id(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, task.version(), context.correlationId());
        receiptStore.complete(
                task.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(result, receipt);
    }

    private static void requireDelegationAuthority(
            Principal actor, List<ResponsibilityAssignment> assignments) {
        boolean authorized = assignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.OWNER
                        || value.role() == ResponsibilityRole.EXECUTOR)
                .anyMatch(value -> value.actorPrincipalId().equals(actor.id()));
        if (!authorized) {
            throw new PolicyDeniedException("delegate this WorkItem to an Agent");
        }
    }

    private static CommandRequestHash requestHash(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            CreateAgentTaskCommand command) {
        List<String> fields = new ArrayList<>();
        fields.add(context.access().actor().id().toString());
        fields.add(teamId.toString());
        fields.add(projectId.toString());
        fields.add(workItemId.toString());
        fields.add(Long.toString(command.expectedWorkItemVersion()));
        fields.add(command.brief().objective());
        fields.add(Integer.toString(command.brief().acceptanceCriteria().size()));
        fields.addAll(command.brief().acceptanceCriteria());
        fields.add(command.executorAgentProfileId().toString());
        fields.add(command.conversationSource()
                .map(value -> value.conversationId().toString())
                .orElse(""));
        fields.add(command.conversationSource()
                .map(value -> value.messageId().toString())
                .orElse(""));
        command.providerBindingIds().stream()
                .sorted(Comparator.comparing(ProviderBindingId::toString))
                .map(ProviderBindingId::toString)
                .forEach(fields::add);
        fields.add(context.causationId().map(UUID::toString).orElse(""));
        return CommandRequestHash.sha256(COMMAND_TYPE, fields.toArray(String[]::new));
    }
}
