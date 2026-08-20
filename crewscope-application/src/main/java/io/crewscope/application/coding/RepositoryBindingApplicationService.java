package io.crewscope.application.coding;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingKeyConflictException;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.event.RepositoryBindingChanged;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
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
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Coordinates RepositoryBinding management, authorization, Preflight and durable commands. */
public final class RepositoryBindingApplicationService {

    private static final String AGGREGATE_TYPE = "REPOSITORY_BINDING";
    private static final String REGISTER = "REGISTER_REPOSITORY_BINDING";
    private static final String ACTIVATE = "ACTIVATE_REPOSITORY_BINDING";
    private static final String DISABLE = "DISABLE_REPOSITORY_BINDING";
    private static final String REGISTERED = "REPOSITORY_BINDING_REGISTERED";
    private static final String ACTIVATED = "REPOSITORY_BINDING_ACTIVATED";
    private static final String DISABLED = "REPOSITORY_BINDING_DISABLED";

    private final RepositoryBindingRepository bindingRepository;
    private final RepositoryBindingAccessPolicy accessPolicy;
    private final RepositoryBindingPreflightPort preflightPort;
    private final DomainEventStore domainEventStore;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore receiptStore;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public RepositoryBindingApplicationService(
            RepositoryBindingRepository bindingRepository,
            RepositoryBindingAccessPolicy accessPolicy,
            RepositoryBindingPreflightPort preflightPort,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.preflightPort = Objects.requireNonNull(preflightPort, "preflightPort");
        this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Registers an active binding only after the managed repository and default Ref pass Preflight. */
    public CommandExecution<RepositoryBinding> create(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            CreateRepositoryBindingCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        WorkProjectId requiredProjectId = Objects.requireNonNull(projectId, "projectId");
        CreateRepositoryBindingCommand required = Objects.requireNonNull(command, "command");
        RepositoryKey repositoryKey = RepositoryKey.parse(required.repositoryKey());
        RepositoryBranchName defaultBranch = new RepositoryBranchName(required.defaultBranch());
        CommandRequestHash requestHash = CommandRequestHash.sha256(
                REGISTER,
                trusted.access().actor().id().toString(),
                requiredTeamId.toString(),
                requiredProjectId.toString(),
                trusted.causationId().map(UUID::toString).orElse(""),
                repositoryKey.value(),
                defaultBranch.value());
        return execute(
                trusted,
                requiredTeamId,
                requiredProjectId,
                REGISTER,
                requestHash,
                (commandId, project, actor, occurredAt) -> {
                    OrganizationId organizationId = actor.scope().organizationId();
                    if (bindingRepository
                            .findByKey(
                                    organizationId,
                                    requiredTeamId,
                                    requiredProjectId,
                                    repositoryKey)
                            .isPresent()) {
                        throw new RepositoryBindingKeyConflictException(
                                requiredProjectId, repositoryKey);
                    }
                    RepositoryBinding binding = RepositoryBinding.registerLocalManaged(
                            RepositoryBindingId.generate(),
                            project,
                            repositoryKey,
                            defaultBranch,
                            actor,
                            occurredAt);
                    preflightPort.preflight(binding, defaultBranch);
                    RepositoryBinding committed = bindingRepository.create(binding);
                    return completed(trusted, commandId, committed, REGISTERED, occurredAt);
                });
    }

    /** Lists bindings only after exact Team and WorkProject visibility has been proven. */
    public List<RepositoryBinding> list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId) {
        accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
        return bindingRepository.findByWorkProject(organizationId, teamId, projectId);
    }

    /** Returns one binding and treats every route or persisted Scope mismatch as not found. */
    public RepositoryBinding get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId) {
        accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
        return requireBinding(organizationId, teamId, projectId, bindingId);
    }

    /** Preflights a path-free Repository Key before a binding is created. */
    public RepositoryBindingPreflightResult preflightDraft(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryKey repositoryKey,
            RepositoryBranchName baselineRef) {
        UtcTimestamp occurredAt = timeProvider.now();
        WorkProject project = accessPolicy.requireAdministrator(
                context, organizationId, teamId, projectId, occurredAt);
        RepositoryBinding candidate = RepositoryBinding.registerLocalManaged(
                RepositoryBindingId.generate(),
                project,
                repositoryKey,
                baselineRef,
                context.actor(),
                occurredAt);
        return preflightPort.preflight(candidate, baselineRef);
    }

    /** Rechecks an existing binding without exposing its resolved host location. */
    public RepositoryBindingPreflightResult preflightExisting(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId) {
        UtcTimestamp occurredAt = timeProvider.now();
        WorkProject project = accessPolicy.requireAdministrator(
                context, organizationId, teamId, projectId, occurredAt);
        RepositoryBinding binding = requireBinding(
                organizationId, teamId, projectId, bindingId);
        return preflightPort.preflight(
                activePreflightCandidate(binding, project, context.actor(), occurredAt),
                binding.defaultBranch());
    }

    /** Re-enables a binding only after current repository Preflight succeeds. */
    public CommandExecution<RepositoryBinding> activate(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId,
            long expectedVersion) {
        return transition(
                context,
                teamId,
                projectId,
                bindingId,
                expectedVersion,
                ACTIVATE,
                ACTIVATED,
                (binding, project, actor, occurredAt) -> {
                    preflightPort.preflight(
                            activePreflightCandidate(binding, project, actor, occurredAt),
                            binding.defaultBranch());
                    return binding.activate(expectedVersion, actor, occurredAt);
                });
    }

    /** Disables selection by future CodingTarget snapshots while preserving historical facts. */
    public CommandExecution<RepositoryBinding> disable(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId,
            long expectedVersion) {
        return transition(
                context,
                teamId,
                projectId,
                bindingId,
                expectedVersion,
                DISABLE,
                DISABLED,
                (binding, project, actor, occurredAt) ->
                        binding.disable(expectedVersion, actor, occurredAt));
    }

    private CommandExecution<RepositoryBinding> transition(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId,
            long expectedVersion,
            String commandType,
            String eventType,
            BindingTransition transition) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        WorkProjectId requiredProjectId = Objects.requireNonNull(projectId, "projectId");
        RepositoryBindingId requiredBindingId = Objects.requireNonNull(bindingId, "bindingId");
        CommandRequestHash requestHash = CommandRequestHash.sha256(
                commandType,
                trusted.access().actor().id().toString(),
                requiredTeamId.toString(),
                requiredProjectId.toString(),
                requiredBindingId.toString(),
                Long.toString(expectedVersion),
                trusted.causationId().map(UUID::toString).orElse(""));
        return execute(
                trusted,
                requiredTeamId,
                requiredProjectId,
                commandType,
                requestHash,
                (commandId, project, actor, occurredAt) -> {
                    OrganizationId organizationId = actor.scope().organizationId();
                    RepositoryBinding binding = requireBinding(
                            organizationId,
                            requiredTeamId,
                            requiredProjectId,
                            requiredBindingId);
                    RepositoryBinding updated =
                            transition.apply(binding, project, actor, occurredAt);
                    RepositoryBinding committed = bindingRepository.update(updated);
                    return completed(trusted, commandId, committed, eventType, occurredAt);
                });
    }

    private CommandExecution<RepositoryBinding> execute(
            TeamCommandContext context,
            TeamId teamId,
            WorkProjectId projectId,
            String commandType,
            CommandRequestHash requestHash,
            AuthorizedBindingCommand command) {
        return transactionExecutor.required(() -> {
            Principal actor = context.access().actor();
            OrganizationId organizationId = actor.scope().organizationId();
            UtcTimestamp now = timeProvider.now();
            // Current authority is checked before both first execution and Receipt replay. A
            // previously authorized caller cannot use an old key after its role is revoked.
            WorkProject project = accessPolicy.requireAdministrator(
                    context.access(), organizationId, teamId, projectId, now);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receiptStore.reserve(new CommandReservationRequest(
                    organizationId,
                    context.idempotencyKey(),
                    commandType,
                    requestHash,
                    commandId,
                    context.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            return command.apply(commandId, project, actor, now);
        });
    }

    private CommandExecution<RepositoryBinding> completed(
            TeamCommandContext context,
            UUID commandId,
            RepositoryBinding binding,
            String eventType,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<RepositoryBindingChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                binding.scope().organizationId(),
                Optional.of(binding.scope().teamId()),
                Optional.of(binding.scope().workspaceId()),
                AggregateReference.of(AGGREGATE_TYPE, binding.id()),
                binding.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                occurredAt,
                RepositoryBindingChanged.from(binding));
        domainEventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, binding.version(), context.correlationId());
        receiptStore.complete(
                binding.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(binding, receipt);
    }

    private RepositoryBinding requireBinding(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            RepositoryBindingId bindingId) {
        return bindingRepository
                .findById(organizationId, teamId, projectId, bindingId)
                .orElseThrow(() -> new AggregateNotFoundException("RepositoryBinding", bindingId));
    }

    private static RepositoryBinding activePreflightCandidate(
            RepositoryBinding binding,
            WorkProject project,
            Principal actor,
            UtcTimestamp occurredAt) {
        if (binding.acceptsNewTargets()) {
            return binding;
        }
        // BaselinePreflight intentionally accepts only active candidates. This transient aggregate
        // preserves the stored Repository Key while the disabled source remains unchanged.
        return RepositoryBinding.registerLocalManaged(
                binding.id(),
                project,
                binding.repositoryKey(),
                binding.defaultBranch(),
                actor,
                occurredAt);
    }

    @FunctionalInterface
    private interface BindingTransition {
        RepositoryBinding apply(
                RepositoryBinding binding,
                WorkProject project,
                Principal actor,
                UtcTimestamp occurredAt);
    }

    @FunctionalInterface
    private interface AuthorizedBindingCommand {
        CommandExecution<RepositoryBinding> apply(
                UUID commandId,
                WorkProject project,
                Principal actor,
                UtcTimestamp occurredAt);
    }
}
