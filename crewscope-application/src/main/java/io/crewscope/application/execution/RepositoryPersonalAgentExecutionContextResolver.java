package io.crewscope.application.execution;

import io.crewscope.application.conversation.AgentRuntimeSessionService;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Repository-backed resolver that establishes the durable Session before the security snapshot. */
public final class RepositoryPersonalAgentExecutionContextResolver
        implements PersonalAgentExecutionContextResolver {

    private final ConversationRepository conversationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final PrincipalRepository principalRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final AgentRuntimeSessionService runtimeSessionService;
    private final PlatformExecutionContextResolver platformContextResolver;

    public RepositoryPersonalAgentExecutionContextResolver(
            ConversationRepository conversationRepository,
            WorkspaceRepository workspaceRepository,
            TeamMemberRepository teamMemberRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository agentProfileRepository,
            AgentRuntimeSessionService runtimeSessionService,
            PlatformExecutionContextResolver platformContextResolver) {
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository, "conversationRepository");
        this.workspaceRepository = Objects.requireNonNull(
                workspaceRepository, "workspaceRepository");
        this.teamMemberRepository = Objects.requireNonNull(
                teamMemberRepository, "teamMemberRepository");
        this.principalRepository = Objects.requireNonNull(
                principalRepository, "principalRepository");
        this.agentProfileRepository = Objects.requireNonNull(
                agentProfileRepository, "agentProfileRepository");
        this.runtimeSessionService = Objects.requireNonNull(
                runtimeSessionService, "runtimeSessionService");
        this.platformContextResolver = Objects.requireNonNull(
                platformContextResolver, "platformContextResolver");
    }

    @Override
    public void requireOwner(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            UUID correlationId) {
        requireOwnerConversation(access, organizationId, teamId, conversationId);
    }

    @Override
    public ResolvedPersonalAgentExecution resolve(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            RuntimeInvocationId invocationId,
            UUID correlationId) {
        TeamAccessContext trusted = Objects.requireNonNull(access, "access");
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        ConversationId conversationIdentity = Objects.requireNonNull(
                conversationId, "conversationId");
        Conversation conversation = requireOwnerConversation(
                trusted, organization, team, conversationIdentity);
        Principal owner = trusted.actor();
        Workspace workspace = workspaceRepository
                .findById(organization, conversation.scope().workspaceId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Workspace", conversation.scope().workspaceId()));
        TeamMember member = teamMemberRepository
                .findById(organization, conversation.ownerMemberId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TeamMember", conversation.ownerMemberId()));
        AgentProfile profile = agentProfileRepository
                .findActiveDefaultPersonal(organization, member.id())
                .orElseThrow(() -> new PolicyDeniedException(
                        "invoke this Conversation's Personal Agent"));
        Principal agent = principalRepository
                .findById(organization, conversation.personalAgentPrincipalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", conversation.personalAgentPrincipalId()));
        PersonalAgentInitialization personalAgent =
                new PersonalAgentInitialization(agent, profile).requireDefaultFor(member, workspace);
        AgentRuntimeSession session = runtimeSessionService.ensurePersonal(
                conversation, workspace, member, owner, personalAgent);
        PlatformExecutionContext platformContext = platformContextResolver.resolve(
                new PlatformExecutionContextResolutionRequest(
                        session,
                        owner.id(),
                        Objects.requireNonNull(invocationId, "invocationId"),
                        Objects.requireNonNull(correlationId, "correlationId"),
                        Map.of()));
        return new ResolvedPersonalAgentExecution(session, platformContext);
    }

    private Conversation requireOwnerConversation(
            TeamAccessContext access,
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId) {
        TeamAccessContext trusted = Objects.requireNonNull(access, "access");
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        TeamId team = Objects.requireNonNull(teamId, "teamId");
        ConversationId identity = Objects.requireNonNull(conversationId, "conversationId");
        Conversation conversation = conversationRepository
                .findById(organization, identity)
                .filter(value -> value.scope().teamId().equals(team))
                .orElseThrow(() -> new AggregateNotFoundException("Conversation", identity));
        Principal owner = trusted.actor();
        if (owner.type() != PrincipalType.USER
                || !owner.canAct()
                || !owner.scope().organizationId().equals(organization)
                || !conversation.ownerPrincipalId().equals(owner.id())) {
            throw new PolicyDeniedException("invoke this Conversation's Personal Agent");
        }
        return conversation;
    }
}
