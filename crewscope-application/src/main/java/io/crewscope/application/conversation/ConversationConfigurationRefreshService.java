package io.crewscope.application.conversation;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.event.AgentRuntimeConfigurationRefreshed;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
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
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owner-only safe-point refresh of a Conversation's pinned Personal Agent configuration. */
public final class ConversationConfigurationRefreshService {

    private static final String REFRESH = "REFRESH_CONVERSATION_AGENT_CONFIGURATION";

    private final ConversationRepository conversations;
    private final WorkspaceRepository workspaces;
    private final TeamMemberRepository members;
    private final PrincipalRepository principals;
    private final AgentProfileRepository profiles;
    private final AgentConfigurationRepository configurations;
    private final AgentRuntimeSessionRepository sessions;
    private final ConversationConfigurationRefreshGuard refreshGuard;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ConversationConfigurationRefreshService(
            ConversationRepository conversations,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentRuntimeSessionRepository sessions,
            ConversationConfigurationRefreshGuard refreshGuard,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.members = Objects.requireNonNull(members, "members");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.refreshGuard = Objects.requireNonNull(refreshGuard, "refreshGuard");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<AgentRuntimeSession> refresh(
            TeamCommandContext context,
            TeamId teamId,
            ConversationId conversationId,
            long expectedSessionVersion) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        if (expectedSessionVersion < 0) {
            throw new IllegalArgumentException("expectedSessionVersion must not be negative");
        }
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash = CommandRequestHash.sha256(
                REFRESH,
                trusted.access().actor().id().toString(),
                teamId.toString(),
                conversationId.toString(),
                Long.toString(expectedSessionVersion));
        return transactions.required(() -> {
            RefreshFacts facts = requireFacts(
                    trusted.access(), organizationId, teamId, conversationId);
            Optional<CommandReceipt> completed = receipts.findCompleted(
                    organizationId, trusted.idempotencyKey(), REFRESH, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            return refreshGuard.atSafePoint(
                    organizationId,
                    teamId,
                    conversationId,
                    () -> refreshAtSafePoint(
                            trusted, facts, requestHash, expectedSessionVersion));
        });
    }

    private CommandExecution<AgentRuntimeSession> refreshAtSafePoint(
            TeamCommandContext context,
            RefreshFacts facts,
            CommandRequestHash requestHash,
            long expectedSessionVersion) {
        UtcTimestamp now = timeProvider.now();
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                facts.session().scope().organizationId(),
                context.idempotencyKey(),
                REFRESH,
                requestHash,
                commandId,
                context.correlationId(),
                now));
        if (!reservation.acquired()) {
            return CommandExecution.replayed(reservation.receipt().orElseThrow());
        }
        AgentRuntimeSession refreshed = facts.session().refreshConfigurationVersion(
                expectedSessionVersion,
                facts.conversation(),
                facts.workspace(),
                facts.member(),
                facts.owner(),
                facts.personalAgent(),
                facts.configuration(),
                facts.owner(),
                now);
        AgentRuntimeSession committed = sessions.update(refreshed);
        return complete(context, commandId, committed, facts.configuration(), now);
    }

    /** Returns the strong Session version and current/pinned revisions needed by the UI. */
    public ConversationConfigurationStatus status(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId) {
        return transactions.required(() -> {
            RefreshFacts facts = requireFacts(context, organizationId, teamId, conversationId);
            return ConversationConfigurationStatus.from(
                    facts.session(), facts.configuration());
        });
    }

    private RefreshFacts requireFacts(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId) {
        Principal owner = Objects.requireNonNull(context, "context").actor();
        if (owner.type() != PrincipalType.USER
                || !owner.canAct()
                || !owner.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("refresh this Conversation configuration");
        }
        Conversation conversation = conversations.findById(organizationId, conversationId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .filter(value -> value.ownerPrincipalId().equals(owner.id()))
                .filter(Conversation::acceptsMessages)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Conversation", conversationId));
        Workspace workspace = workspaces
                .findById(organizationId, conversation.scope().workspaceId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Workspace", conversation.scope().workspaceId()));
        TeamMember member = members
                .findById(organizationId, conversation.ownerMemberId())
                .filter(TeamMember::canParticipate)
                .filter(value -> value.scope().teamId().equals(teamId))
                .filter(value -> value.userPrincipalId().equals(owner.id()))
                .orElseThrow(() -> new PolicyDeniedException(
                        "refresh this Conversation configuration"));
        AgentProfile profile = profiles
                .findActiveDefaultPersonal(organizationId, member.id())
                .orElseThrow(() -> new PolicyDeniedException(
                        "refresh this Conversation's Personal Agent"));
        Principal agent = principals
                .findById(organizationId, profile.agentPrincipalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", profile.agentPrincipalId()));
        PersonalAgentInitialization personalAgent = new PersonalAgentInitialization(agent, profile)
                .requireDefaultFor(member, workspace);
        AgentRuntimeSession session = sessions
                .findActiveByConversation(organizationId, conversationId)
                .filter(value -> value.scope().equals(conversation.scope()))
                .filter(value -> value.agentProfileId().equals(profile.id()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentRuntimeSession", conversationId));
        AgentConfigurationVersion configuration = configurations
                .findCurrent(organizationId, profile.id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentConfiguration", profile.id()));
        return new RefreshFacts(
                owner, conversation, workspace, member, personalAgent, session, configuration);
    }

    private CommandExecution<AgentRuntimeSession> complete(
            TeamCommandContext context,
            UUID commandId,
            AgentRuntimeSession session,
            AgentConfigurationVersion configuration,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<AgentRuntimeConfigurationRefreshed> event =
                new DomainEventEnvelope<>(
                        eventId,
                        EventType.from("AGENT_RUNTIME_CONFIGURATION_REFRESHED"),
                        SchemaVersion.V1,
                        session.scope().organizationId(),
                        Optional.of(session.scope().teamId()),
                        Optional.of(session.scope().workspaceId()),
                        AggregateReference.of("AGENT_RUNTIME_SESSION", session.id()),
                        session.version(),
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt,
                        AgentRuntimeConfigurationRefreshed.from(session, configuration));
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, session.version(), context.correlationId());
        receipts.complete(
                session.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(session, receipt);
    }

    private record RefreshFacts(
            Principal owner,
            Conversation conversation,
            Workspace workspace,
            TeamMember member,
            PersonalAgentInitialization personalAgent,
            AgentRuntimeSession session,
            AgentConfigurationVersion configuration) {}
}
