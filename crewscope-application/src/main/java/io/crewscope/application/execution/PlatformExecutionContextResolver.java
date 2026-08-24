package io.crewscope.application.execution;

import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ProviderBindingResolution;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationAccessDecision;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScopeType;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rebuilds one credential-free PlatformExecutionContext from current server-owned facts. */
public final class PlatformExecutionContextResolver {

    private final AgentRuntimeSessionRepository runtimeSessionRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final TeamRepository teamRepository;
    private final WorkspaceRepository workspaceRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final PrincipalRepository principalRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final TeamRoleRepository teamRoleRepository;
    private final ProviderBindingResolver providerBindingResolver;
    private final TimeProvider timeProvider;
    private final ConversationVisibilityPolicy visibilityPolicy;

    public PlatformExecutionContextResolver(
            AgentRuntimeSessionRepository runtimeSessionRepository,
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository,
            TeamRepository teamRepository,
            WorkspaceRepository workspaceRepository,
            TeamMemberRepository teamMemberRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository agentProfileRepository,
            MemberRoleRepository memberRoleRepository,
            TeamRoleRepository teamRoleRepository,
            ProviderBindingResolver providerBindingResolver,
            TimeProvider timeProvider,
            ConversationVisibilityPolicy visibilityPolicy) {
        this.runtimeSessionRepository = Objects.requireNonNull(
                runtimeSessionRepository, "runtimeSessionRepository");
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository, "conversationRepository");
        this.participantRepository = Objects.requireNonNull(
                participantRepository, "participantRepository");
        this.teamRepository = Objects.requireNonNull(teamRepository, "teamRepository");
        this.workspaceRepository = Objects.requireNonNull(
                workspaceRepository, "workspaceRepository");
        this.teamMemberRepository = Objects.requireNonNull(
                teamMemberRepository, "teamMemberRepository");
        this.principalRepository = Objects.requireNonNull(
                principalRepository, "principalRepository");
        this.agentProfileRepository = Objects.requireNonNull(
                agentProfileRepository, "agentProfileRepository");
        this.memberRoleRepository = Objects.requireNonNull(
                memberRoleRepository, "memberRoleRepository");
        this.teamRoleRepository = Objects.requireNonNull(
                teamRoleRepository, "teamRoleRepository");
        this.providerBindingResolver = Objects.requireNonNull(
                providerBindingResolver, "providerBindingResolver");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.visibilityPolicy = Objects.requireNonNull(visibilityPolicy, "visibilityPolicy");
    }

    /** Resolves current membership, participation, profile, role and Provider authorization. */
    public PlatformExecutionContext resolve(PlatformExecutionContextResolutionRequest request) {
        PlatformExecutionContextResolutionRequest required = Objects.requireNonNull(
                request, "request");
        AgentRuntimeSession suppliedSession = required.runtimeSession();
        var scope = suppliedSession.scope();
        AgentRuntimeSession session = runtimeSessionRepository
                .findById(scope.organizationId(), suppliedSession.id())
                .orElseThrow(() -> failure("EXECUTION_SESSION_UNAVAILABLE"));
        requireCurrentSession(suppliedSession, session);

        Team team = teamRepository.findById(scope.organizationId(), scope.teamId())
                .filter(Team::isActive)
                .orElseThrow(() -> failure("TEAM_UNAVAILABLE"));
        Workspace workspace = workspaceRepository
                .findById(scope.organizationId(), scope.workspaceId())
                .filter(value -> value.status() == WorkspaceStatus.ACTIVE)
                .filter(value -> value.type() == WorkspaceType.TEAM)
                .filter(value -> value.scope().teamId().filter(scope.teamId()::equals).isPresent())
                .orElseThrow(() -> failure("WORKSPACE_SCOPE_UNAVAILABLE"));
        if (!team.id().equals(scope.teamId())) {
            throw failure("TEAM_SCOPE_MISMATCH");
        }

        TeamMember member = teamMemberRepository
                .findById(scope.organizationId(), session.ownerMemberId())
                .filter(TeamMember::canParticipate)
                .filter(value -> value.scope().teamId().equals(scope.teamId()))
                .orElseThrow(() -> failure("TEAM_MEMBERSHIP_UNAVAILABLE"));
        Principal user = requirePrincipal(
                scope.organizationId(), required.authenticatedPrincipalId(), PrincipalType.USER,
                "REQUEST_PRINCIPAL_UNAVAILABLE");
        if (!user.id().equals(session.ownerPrincipalId())
                || !user.id().equals(member.userPrincipalId())) {
            throw failure("REQUEST_PRINCIPAL_MISMATCH");
        }
        Principal agent = requirePrincipal(
                scope.organizationId(), session.personalAgentPrincipalId(),
                PrincipalType.PERSONAL_AGENT, "PERSONAL_AGENT_UNAVAILABLE");
        if (agent.ownerPrincipalId().filter(user.id()::equals).isEmpty()) {
            throw failure("PERSONAL_AGENT_OWNER_MISMATCH");
        }

        Conversation conversation = conversationRepository
                .findById(scope.organizationId(), session.conversationId())
                .filter(value -> value.scope().equals(scope))
                .filter(Conversation::acceptsMessages)
                .orElseThrow(() -> failure("CONVERSATION_SCOPE_UNAVAILABLE"));
        if (!conversation.ownerMemberId().equals(member.id())
                || !conversation.ownerPrincipalId().equals(user.id())
                || !conversation.personalAgentPrincipalId().equals(agent.id())) {
            throw failure("CONVERSATION_BINDING_MISMATCH");
        }

        List<ConversationParticipant> participants = participantRepository.findByConversation(
                scope.organizationId(), conversation.id());
        ConversationParticipant userParticipant = uniqueActiveParticipant(
                participants, user, ConversationParticipantRole.OWNER,
                "USER_PARTICIPANT_UNAVAILABLE");
        ConversationParticipant agentParticipant = uniqueActiveParticipant(
                participants, agent, ConversationParticipantRole.AGENT,
                "AGENT_PARTICIPANT_UNAVAILABLE");
        ConversationAccessDecision userAccess = visibilityPolicy.forMember(
                conversation, member, user, java.util.Optional.of(userParticipant));
        ConversationAccessDecision agentAccess = visibilityPolicy.forAgent(
                conversation, agentParticipant, agent);
        if (!userAccess.writable() || !agentAccess.writable()) {
            throw failure("CONVERSATION_WRITE_SCOPE_DENIED");
        }

        AgentProfile profile = agentProfileRepository
                .findById(scope.organizationId(), session.agentProfileId())
                .filter(AgentProfile::isActiveDefaultPersonal)
                .filter(value -> value.version() == session.agentProfileVersion())
                .filter(value -> value.workspaceId().equals(scope.workspaceId()))
                .filter(value -> value.ownerMemberId().filter(member.id()::equals).isPresent())
                .filter(value -> value.agentPrincipalId().equals(agent.id()))
                .orElseThrow(() -> failure("AGENT_PROFILE_UNAVAILABLE"));

        RoleFacts roleFacts = resolveRoleFacts(scope.organizationId(), member, timeProvider.now());
        EnumMap<ProviderType, ResolvedProviderBinding> bindings =
                new EnumMap<>(ProviderType.class);
        required.providerRequirements().forEach((type, bindingRequest) -> {
            ProviderBindingResolution resolution = providerBindingResolver.resolve(bindingRequest);
            if (!resolution.isResolved()) {
                // Do not reveal whether the failure was absence, revocation or ambiguity.
                throw failure("PROVIDER_BINDING_UNAVAILABLE");
            }
            bindings.put(type, ResolvedProviderBinding.from(
                    resolution.candidate().orElseThrow()));
        });

        return new PlatformExecutionContext(
                scope,
                workspace.type(),
                user.id(),
                member.id(),
                roleFacts.keys(),
                roleFacts.permissions(),
                agent.id(),
                profile.id(),
                profile.version(),
                conversation.id(),
                conversation.visibility(),
                userParticipant.id(),
                agentParticipant.id(),
                session.id(),
                session.agentScopeKey(),
                required.invocationId(),
                required.correlationId(),
                required.providerRequirements().keySet(),
                bindings);
    }

    private Principal requirePrincipal(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            io.crewscope.domain.shared.id.PrincipalId principalId,
            PrincipalType type,
            String failureCode) {
        return principalRepository.findById(organizationId, principalId)
                .filter(Principal::canAct)
                .filter(value -> value.type() == type)
                .orElseThrow(() -> failure(failureCode));
    }

    private static ConversationParticipant uniqueActiveParticipant(
            List<ConversationParticipant> participants,
            Principal principal,
            ConversationParticipantRole role,
            String failureCode) {
        List<ConversationParticipant> matches = participants.stream()
                .filter(ConversationParticipant::isActive)
                .filter(value -> value.principalId().equals(principal.id()))
                .filter(value -> value.role() == role)
                .toList();
        if (matches.size() != 1) {
            throw failure(failureCode);
        }
        return matches.get(0);
    }

    private RoleFacts resolveRoleFacts(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            TeamMember member,
            UtcTimestamp now) {
        Map<io.crewscope.domain.team.TeamRoleId, TeamRole> rolesById = new HashMap<>();
        teamRoleRepository.findByTeam(organizationId, member.scope().teamId()).stream()
                .filter(TeamRole::isGrantable)
                .forEach(role -> rolesById.put(role.id(), role));
        Set<TeamRoleKey> keys = new LinkedHashSet<>();
        Set<TeamPermission> permissions = new LinkedHashSet<>();
        List<MemberRole> effectiveGrants = new ArrayList<>();
        for (MemberRole grant : memberRoleRepository.findByMember(organizationId, member.id())) {
            if (grant.status() == MemberRoleStatus.ACTIVE
                    && grant.isEffectiveAt(now)
                    && grant.teamScope().equals(member.scope())
                    && grant.roleScope().type() == RoleScopeType.TEAM) {
                effectiveGrants.add(grant);
            }
        }
        for (MemberRole grant : effectiveGrants) {
            TeamRole role = rolesById.get(grant.teamRoleId());
            if (role == null || !role.scope().equals(member.scope())) {
                throw failure("TEAM_ROLE_UNAVAILABLE");
            }
            keys.add(role.key());
            permissions.addAll(role.permissions());
        }
        if (keys.isEmpty()) {
            throw failure("TEAM_ROLE_UNAVAILABLE");
        }
        return new RoleFacts(keys, permissions);
    }

    private static void requireCurrentSession(
            AgentRuntimeSession supplied, AgentRuntimeSession current) {
        if (!current.canInvoke()
                || !current.id().equals(supplied.id())
                || !current.scope().equals(supplied.scope())
                || !current.conversationId().equals(supplied.conversationId())
                || !current.ownerMemberId().equals(supplied.ownerMemberId())
                || !current.ownerPrincipalId().equals(supplied.ownerPrincipalId())
                || !current.personalAgentPrincipalId().equals(supplied.personalAgentPrincipalId())
                || !current.agentProfileId().equals(supplied.agentProfileId())
                || current.agentProfileVersion() != supplied.agentProfileVersion()
                || !current.configurationPin().equals(supplied.configurationPin())
                || !current.agentScopeKey().equals(supplied.agentScopeKey())) {
            throw failure("EXECUTION_SESSION_STALE");
        }
    }

    private static PlatformExecutionContextResolutionException failure(String code) {
        return new PlatformExecutionContextResolutionException(code);
    }

    private record RoleFacts(Set<TeamRoleKey> keys, Set<TeamPermission> permissions) {
        private RoleFacts {
            keys = Set.copyOf(Objects.requireNonNull(keys, "keys"));
            permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        }
    }
}
