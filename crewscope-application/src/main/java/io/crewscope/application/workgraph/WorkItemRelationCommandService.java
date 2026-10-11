package io.crewscope.application.workgraph;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationConflictException;
import io.crewscope.domain.workgraph.WorkItemRelationCycleException;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workgraph.WorkItemSplitSourceConflictException;
import io.crewscope.domain.workgraph.event.WorkItemRelationCreated;
import io.crewscope.domain.workgraph.event.WorkItemRelationRemoved;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes authorized WorkItemRelation commands under the ADR-033 transaction protocol: lock
 * both endpoint WorkProject rows in ascending id order, re-check permissions and duplicates,
 * probe for cycles with the recursive CTE <em>inside</em> the lock, then insert and emit the
 * event — all in one transaction. Relations are immutable facts (aggregate version 0).
 */
public final class WorkItemRelationCommandService {

    private static final String WORK_ITEM_RELATION_AGGREGATE = "WORK_ITEM_RELATION";
    private static final String CREATE_WORK_ITEM_RELATION = "CREATE_WORK_ITEM_RELATION";
    private static final String REMOVE_WORK_ITEM_RELATION = "REMOVE_WORK_ITEM_RELATION";

    private final WorkItemRelationRepository relationRepository;
    private final WorkItemRelationGraphRepository graphRepository;
    private final WorkItemRepository workItemRepository;
    private final WorkProjectRepository projectRepository;
    private final TeamRepository teamRepository;
    private final TeamMembershipQuery membershipQuery;
    private final TeamRoleRepository teamRoleRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final DomainEventStore domainEventStore;
    private final OutboxRepository outboxRepository;
    private final CommandReceiptStore receiptStore;
    private final TransactionExecutor transactionExecutor;
    private final TimeProvider timeProvider;

    public WorkItemRelationCommandService(
            WorkItemRelationRepository relationRepository,
            WorkItemRelationGraphRepository graphRepository,
            WorkItemRepository workItemRepository,
            WorkProjectRepository projectRepository,
            TeamRepository teamRepository,
            TeamMembershipQuery membershipQuery,
            TeamRoleRepository teamRoleRepository,
            MemberRoleRepository memberRoleRepository,
            DomainEventStore domainEventStore,
            OutboxRepository outboxRepository,
            CommandReceiptStore receiptStore,
            TransactionExecutor transactionExecutor,
            TimeProvider timeProvider) {
        this.relationRepository = Objects.requireNonNull(relationRepository, "relationRepository");
        this.graphRepository = Objects.requireNonNull(graphRepository, "graphRepository");
        this.workItemRepository = Objects.requireNonNull(workItemRepository, "workItemRepository");
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
        this.membershipQuery = Objects.requireNonNull(membershipQuery, "membershipQuery");
        this.teamRoleRepository = Objects.requireNonNull(teamRoleRepository, "teamRoleRepository");
        this.memberRoleRepository =
                Objects.requireNonNull(memberRoleRepository, "memberRoleRepository");
        this.domainEventStore = Objects.requireNonNull(domainEventStore, "domainEventStore");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.transactionExecutor =
                Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Creates one relation edge between two WorkItems of the same Team (ADR-033). */
    public CommandExecution<WorkItemRelation> create(
            TeamCommandContext context,
            TeamId teamId,
            CreateWorkItemRelationCommand command) {
        TeamCommandContext trusted = requireCommandContext(context);
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        CreateWorkItemRelationCommand required = Objects.requireNonNull(command, "command");
        CommandRequestHash requestHash =
                CommandRequestHash.sha256(
                        CREATE_WORK_ITEM_RELATION,
                        trusted.access().actor().id().toString(),
                        requiredTeamId.toString(),
                        required.type().name(),
                        required.from().toString(),
                        required.to().toString(),
                        trusted.causationId().map(UUID::toString).orElse(""));
        return execute(
                trusted,
                CREATE_WORK_ITEM_RELATION,
                requestHash,
                commandId ->
                        createInTransaction(trusted, commandId, requiredTeamId, required));
    }

    /** Deletes one relation edge after re-checking permissions on both endpoint projects. */
    public CommandExecution<WorkItemRelation> remove(
            TeamCommandContext context,
            TeamId teamId,
            RemoveWorkItemRelationCommand command) {
        TeamCommandContext trusted = requireCommandContext(context);
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        RemoveWorkItemRelationCommand required = Objects.requireNonNull(command, "command");
        CommandRequestHash requestHash =
                CommandRequestHash.sha256(
                        REMOVE_WORK_ITEM_RELATION,
                        trusted.access().actor().id().toString(),
                        requiredTeamId.toString(),
                        required.relationId().toString(),
                        trusted.causationId().map(UUID::toString).orElse(""));
        return execute(
                trusted,
                REMOVE_WORK_ITEM_RELATION,
                requestHash,
                requestId -> removeInTransaction(trusted, requestId, requiredTeamId, required));
    }

    /**
     * Cascade hook for a future WorkItem deletion protocol: removes every edge touching one
     * WorkItem in this transaction and emits one Removed event per deleted edge. The product
     * itself never deletes WorkItems today (ARCHIVED carries that), so nothing calls this yet.
     * There is deliberately no command receipt — a cascade is one effect of a future delete
     * command, not a command of its own.
     */
    public List<WorkItemRelation> removeAllForWorkItem(
            TeamCommandContext context, TeamId teamId, WorkItemId workItemId) {
        TeamCommandContext trusted = requireCommandContext(context);
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        WorkItemId requiredWorkItemId = Objects.requireNonNull(workItemId, "workItemId");
        return transactionExecutor.required(
                () -> {
                    Principal actor = trusted.access().actor();
                    OrganizationId organizationId = actor.scope().organizationId();
                    Team team = requireTeam(organizationId, requiredTeamId);
                    WorkItem item =
                            workItemRepository
                                    .findById(organizationId, requiredWorkItemId)
                                    .filter(
                                            candidate ->
                                                    candidate
                                                            .scope()
                                                            .teamId()
                                                            .equals(team.id()))
                                    .orElseThrow(
                                            () ->
                                                    new AggregateNotFoundException(
                                                            "WorkItem", requiredWorkItemId));
                    UtcTimestamp occurredAt = timeProvider.now();
                    TeamMember member = requireActiveMember(actor, team);
                    requirePermission(
                            member,
                            TeamPermission.WORK_PARTICIPATE,
                            item.scope().projectId(),
                            occurredAt,
                            "remove relations of WorkItems in this WorkProject");
                    projectRepository.lockByIds(organizationId, List.of(item.scope().projectId()));
                    List<WorkItemRelation> removed =
                            relationRepository.removeByWorkItem(organizationId, item.id());
                    for (WorkItemRelation relation : removed) {
                        emitRemoved(trusted, relation, occurredAt);
                    }
                    return removed;
                });
    }

    private CommandExecution<WorkItemRelation> createInTransaction(
            TeamCommandContext context,
            UUID commandId,
            TeamId teamId,
            CreateWorkItemRelationCommand command) {
        Principal actor = context.access().actor();
        OrganizationId organizationId = actor.scope().organizationId();
        Team team = requireTeam(organizationId, teamId);
        WorkItem fromItem = requireWorkItem(organizationId, team.id(), command.from());
        WorkItem toItem = requireWorkItem(organizationId, team.id(), command.to());
        UtcTimestamp occurredAt = timeProvider.now();
        WorkItemRelation relation =
                WorkItemRelation.relate(
                        WorkItemRelationId.generate(),
                        command.type(),
                        fromItem,
                        toItem,
                        actor.id(),
                        occurredAt);
        // serialize concurrent edge writes on the endpoint projects before probing (ADR-033):
        // lockByIds locks in ascending id order, so two opposite edges can never pass their
        // probes in interleaved transactions
        projectRepository.lockByIds(
                organizationId,
                List.of(
                        relation.src().scope().projectId(),
                        relation.dst().scope().projectId()));
        TeamMember member = requireActiveMember(actor, team);
        requirePermission(
                member,
                TeamPermission.WORK_PARTICIPATE,
                relation.src().scope().projectId(),
                occurredAt,
                "relate WorkItems in the source WorkProject");
        requirePermission(
                member,
                TeamPermission.WORK_PARTICIPATE,
                relation.dst().scope().projectId(),
                occurredAt,
                "relate WorkItems in the target WorkProject");
        requireCollaboratingEndpoints(fromItem, toItem);
        requireEdgeAbsent(organizationId, relation);
        requireAcyclic(organizationId, relation);
        relationRepository.create(relation);
        return completed(
                context,
                commandId,
                relation,
                EventType.from("WORK_ITEM_RELATION_CREATED"),
                WorkItemRelationCreated.from(relation),
                occurredAt);
    }

    private CommandExecution<WorkItemRelation> removeInTransaction(
            TeamCommandContext context,
            UUID commandId,
            TeamId teamId,
            RemoveWorkItemRelationCommand command) {
        Principal actor = context.access().actor();
        OrganizationId organizationId = actor.scope().organizationId();
        Team team = requireTeam(organizationId, teamId);
        WorkItemRelation existing =
                relationRepository
                        .findById(organizationId, command.relationId())
                        .orElseThrow(
                                () ->
                                        new AggregateNotFoundException(
                                                "WorkItemRelation", command.relationId()));
        requireSameTeam(existing, team.id());
        UtcTimestamp occurredAt = timeProvider.now();
        projectRepository.lockByIds(
                organizationId,
                List.of(
                        existing.src().scope().projectId(),
                        existing.dst().scope().projectId()));
        TeamMember member = requireActiveMember(actor, team);
        requirePermission(
                member,
                TeamPermission.WORK_PARTICIPATE,
                existing.src().scope().projectId(),
                occurredAt,
                "remove relations of WorkItems in the source WorkProject");
        requirePermission(
                member,
                TeamPermission.WORK_PARTICIPATE,
                existing.dst().scope().projectId(),
                occurredAt,
                "remove relations of WorkItems in the target WorkProject");
        WorkItemRelation removed =
                relationRepository
                        .removeById(organizationId, existing.id())
                        .orElseThrow(
                                () ->
                                        new AggregateNotFoundException(
                                                "WorkItemRelation", existing.id()));
        return completed(
                context,
                commandId,
                removed,
                EventType.from("WORK_ITEM_RELATION_REMOVED"),
                WorkItemRelationRemoved.from(removed),
                occurredAt);
    }

    /**
     * New edges may not touch an ARCHIVED endpoint (M11-A02): archived WorkItems stop
     * accepting relations the way they stop accepting comments. Checked after the permission
     * protocol so a non-participant cannot probe endpoint states, and only on create — an
     * existing edge stays visible and removable after an endpoint is archived.
     */
    private static void requireCollaboratingEndpoints(WorkItem from, WorkItem to) {
        if (!from.acceptsCollaboration() || !to.acceptsCollaboration()) {
            throw new DomainValidationException(
                    "workItemRelation.endpoints", "must not reference an archived WorkItem");
        }
    }

    /** Same-direction duplicates and second SPLIT_FROM sources are rejected before probing. */
    private void requireEdgeAbsent(OrganizationId organizationId, WorkItemRelation relation) {
        if (relationRepository
                .findByStoredEndpoints(
                        organizationId,
                        relation.src().workItemId(),
                        relation.dst().workItemId())
                .isPresent()) {
            throw new WorkItemRelationConflictException(
                    relation.src().workItemId(), relation.dst().workItemId());
        }
        if (relation.kind() == WorkItemRelationType.SPLIT_FROM) {
            boolean alreadySpawned =
                    relationRepository
                            .findByEndpoint(organizationId, relation.src().workItemId())
                            .stream()
                            .anyMatch(
                                    edge ->
                                            edge.kind() == WorkItemRelationType.SPLIT_FROM
                                                    && edge.src()
                                                            .workItemId()
                                                            .equals(
                                                                    relation.src().workItemId()));
            if (alreadySpawned) {
                throw new WorkItemSplitSourceConflictException(relation.src().workItemId());
            }
        }
    }

    /**
     * The authoritative cycle check, run inside the work-project locks: inserting {@code src
     * BLOCKS dst} closes a cycle exactly when the blocking closure of {@code dst} already
     * reaches {@code src}; the split forest asks the mirrored ancestry question. The probes
     * carry no depth bound — a truncated probe could admit a cycle (ADR-033).
     */
    private void requireAcyclic(OrganizationId organizationId, WorkItemRelation relation) {
        if (relation.kind() == WorkItemRelationType.SPLIT_FROM) {
            if (graphRepository.splitAncestorOf(
                    organizationId, relation.dst().workItemId(), relation.src().workItemId())) {
                throw new WorkItemRelationCycleException(
                        relation.src().workItemId(), relation.dst().workItemId());
            }
            return;
        }
        if (graphRepository.blocksReaches(
                organizationId, relation.dst().workItemId(), relation.src().workItemId())) {
            throw new WorkItemRelationCycleException(
                    relation.src().workItemId(), relation.dst().workItemId());
        }
    }

    private CommandExecution<WorkItemRelation> execute(
            TeamCommandContext context,
            String commandType,
            CommandRequestHash requestHash,
            Function<UUID, CommandExecution<WorkItemRelation>> command) {
        return transactionExecutor.required(
                () -> {
                    UtcTimestamp now = timeProvider.now();
                    UUID commandId = UUID.randomUUID();
                    CommandReservation reservation =
                            receiptStore.reserve(
                                    new CommandReservationRequest(
                                            context.access().actor().scope().organizationId(),
                                            context.idempotencyKey(),
                                            commandType,
                                            requestHash,
                                            commandId,
                                            context.correlationId(),
                                            now));
                    if (!reservation.acquired()) {
                        return CommandExecution.replayed(reservation.receipt().orElseThrow());
                    }
                    return command.apply(commandId);
                });
    }

    private CommandExecution<WorkItemRelation> completed(
            TeamCommandContext context,
            UUID commandId,
            WorkItemRelation relation,
            EventType eventType,
            DomainEvent payload,
            UtcTimestamp occurredAt) {
        UUID eventId =
                emitEvent(
                        context,
                        relation,
                        eventType,
                        payload,
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt);
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, 0, context.correlationId());
        receiptStore.complete(
                relation.src().scope().organizationId(),
                context.idempotencyKey(),
                receipt,
                occurredAt);
        return CommandExecution.completed(relation, receipt);
    }

    private void emitRemoved(
            TeamCommandContext context, WorkItemRelation relation, UtcTimestamp occurredAt) {
        // cascaded removals are effects of one future delete command, not commands of their own:
        // several edges go in one transaction, so the envelopes carry no idempotency key — the
        // partial unique index ux_domain_event_idempotency leaves NULL free for system facts
        emitEvent(
                context,
                relation,
                EventType.from("WORK_ITEM_RELATION_REMOVED"),
                WorkItemRelationRemoved.from(relation),
                Optional.empty(),
                occurredAt);
    }

    private UUID emitEvent(
            TeamCommandContext context,
            WorkItemRelation relation,
            EventType eventType,
            DomainEvent payload,
            Optional<String> idempotencyKey,
            UtcTimestamp occurredAt) {
        // one edge spans exactly one Team (enforced above); the workspace is only carried when
        // both endpoints share it, which is exactly when a workspace-scoped consumer could care
        Optional<WorkspaceId> workspace = workspaceOf(relation);
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event =
                new DomainEventEnvelope<>(
                        eventId,
                        eventType,
                        SchemaVersion.V1,
                        relation.src().scope().organizationId(),
                        Optional.of(relation.src().scope().teamId()),
                        workspace,
                        AggregateReference.of(WORK_ITEM_RELATION_AGGREGATE, relation.id()),
                        0,
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        idempotencyKey,
                        occurredAt,
                        payload);
        domainEventStore.append(event);
        outboxRepository.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return eventId;
    }

    private static Optional<WorkspaceId> workspaceOf(WorkItemRelation relation) {
        WorkspaceId src = relation.src().scope().workspaceId();
        WorkspaceId dst = relation.dst().scope().workspaceId();
        return src.equals(dst) ? Optional.of(src) : Optional.empty();
    }

    private WorkItem requireWorkItem(OrganizationId organizationId, TeamId teamId, WorkItemId id) {
        WorkItem item =
                workItemRepository
                        .findById(organizationId, id)
                        .orElseThrow(() -> new AggregateNotFoundException("WorkItem", id));
        // cross-Team edges are refused explicitly, never masked as "not found"
        if (!item.scope().teamId().equals(teamId)) {
            throw new PolicyDeniedException("relate WorkItems across Teams");
        }
        return item;
    }

    private static void requireSameTeam(WorkItemRelation relation, TeamId teamId) {
        if (!relation.src().scope().teamId().equals(teamId)
                || !relation.dst().scope().teamId().equals(teamId)) {
            throw new PolicyDeniedException("remove relations across Teams");
        }
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teamRepository.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException(
                    "team.initializationStatus", "must be READY");
        }
        return teamRepository
                .findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    }

    private TeamMember requireActiveMember(Principal actor, Team team) {
        requireActiveUserInOrganization(actor, team.organizationId());
        return membershipQuery.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's WorkItems"));
    }

    private void requirePermission(
            TeamMember member,
            TeamPermission permission,
            WorkProjectId projectId,
            UtcTimestamp occurredAt,
            String action) {
        Map<TeamRoleId, TeamRole> roles =
                teamRoleRepository
                        .findByTeam(member.scope().organizationId(), member.scope().teamId())
                        .stream()
                        .collect(Collectors.toMap(TeamRole::id, role -> role));
        RoleScope projectScope = RoleScope.workProject(projectId);
        boolean allowed =
                memberRoleRepository.findByMember(member.scope().organizationId(), member.id())
                        .stream()
                        .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                        .filter(grant -> grant.isEffectiveAt(occurredAt))
                        .filter(
                                grant ->
                                        grant.roleScope().equals(RoleScope.team())
                                                || grant.roleScope().equals(projectScope))
                        .map(grant -> roles.get(grant.teamRoleId()))
                        .filter(Objects::nonNull)
                        .filter(TeamRole::isGrantable)
                        .anyMatch(role -> role.permissions().contains(permission));
        if (!allowed) {
            throw new PolicyDeniedException(action);
        }
    }

    private static TeamCommandContext requireCommandContext(TeamCommandContext context) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        requireActiveUserInOrganization(
                trusted.access().actor(), trusted.access().actor().scope().organizationId());
        return trusted;
    }

    private static void requireActiveUserInOrganization(
            Principal principal, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(principal, "principal");
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
    }
}
