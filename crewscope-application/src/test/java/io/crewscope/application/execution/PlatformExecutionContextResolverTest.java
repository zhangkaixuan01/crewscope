package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBindingResolutionRequest;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** Locks reconstruction of every M2 execution fact to current server-side repositories. */
class PlatformExecutionContextResolverTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-09T06:00:00Z");

    @Test
    void resolvesCurrentMembershipParticipantsRolesProfileAndSession() {
        Fixture fixture = Fixture.create();

        PlatformExecutionContext context = fixture.resolver().resolve(fixture.request(Map.of()));

        assertEquals(fixture.owner.id(), context.requestPrincipalId());
        assertEquals(fixture.session.id(), context.runtimeSessionId());
        assertEquals(fixture.conversation.conversation().id(), context.conversationId());
        assertEquals(
                fixture.initialization.ownerPersonalAgent().agentProfile().id(),
                context.agentProfileId());
        assertTrue(context.teamRoleKeys().stream()
                .anyMatch(key -> "TEAM_OWNER".equals(key.value())));
        assertTrue(context.teamPermissions().contains(
                io.crewscope.domain.team.TeamPermission.AUDIT_READ));
        assertTrue(context.providerBindings().isEmpty());
    }

    @Test
    void failsClosedWhenMembershipParticipantScopeOrProfileIsMissing() {
        Fixture fixture = Fixture.create();

        assertSafeCode(
                "TEAM_MEMBERSHIP_UNAVAILABLE",
                fixture.withoutMember().resolver(),
                fixture.request(Map.of()));
        assertSafeCode(
                "USER_PARTICIPANT_UNAVAILABLE",
                fixture.withoutUserParticipant().resolver(),
                fixture.request(Map.of()));
        assertSafeCode(
                "WORKSPACE_SCOPE_UNAVAILABLE",
                fixture.withoutWorkspace().resolver(),
                fixture.request(Map.of()));
        assertSafeCode(
                "AGENT_PROFILE_UNAVAILABLE",
                fixture.withoutProfile().resolver(),
                fixture.request(Map.of()));
    }

    @Test
    void failsClosedWithoutLeakingUnavailableProviderBindingDetails() {
        Fixture fixture = Fixture.create();
        ProviderBindingResolutionRequest bindingRequest = new ProviderBindingResolutionRequest(
                fixture.initialization.team().organizationId(),
                fixture.initialization.team().id(),
                fixture.initialization.defaultWorkspace().id(),
                Optional.empty(),
                ProviderOwner.user(fixture.owner),
                ProviderType.WORK_ITEM,
                Optional.empty(),
                new ProviderAccessScope(
                        ProviderCapabilities.of("work.read"),
                        ProviderResourceScope.allResources()),
                Optional.empty(),
                Optional.empty());

        PlatformExecutionContextResolutionException failure = assertThrows(
                PlatformExecutionContextResolutionException.class,
                () -> fixture.resolver().resolve(
                        fixture.request(Map.of(ProviderType.WORK_ITEM, bindingRequest))));

        assertEquals("PROVIDER_BINDING_UNAVAILABLE", failure.safeCode());
        assertEquals(
                "The current execution authorization facts could not be resolved.",
                failure.getMessage());
    }

    private static void assertSafeCode(
            String expected,
            PlatformExecutionContextResolver resolver,
            PlatformExecutionContextResolutionRequest request) {
        PlatformExecutionContextResolutionException failure = assertThrows(
                PlatformExecutionContextResolutionException.class,
                () -> resolver.resolve(request));
        assertEquals(expected, failure.safeCode());
    }

    private record Fixture(
            Principal owner,
            TeamInitialization initialization,
            PersonalConversationInitialization conversation,
            AgentRuntimeSession session,
            boolean memberPresent,
            boolean userParticipantPresent,
            boolean workspacePresent,
            boolean profilePresent) {

        private static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Execution Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
            TeamInitialization initialization = TeamInitialization.create(
                    owner, "Execution Team", NOW);
            PersonalConversationInitialization conversation =
                    PersonalConversationInitialization.start(
                            ConversationId.generate(),
                            initialization.defaultWorkspace(),
                            initialization.ownerMember(),
                            owner,
                            initialization.ownerPersonalAgent(),
                            "Trusted execution",
                            ConversationVisibility.PRIVATE,
                            NOW);
            AgentRuntimeSession session = AgentRuntimeSession.initializePersonal(
                    conversation.conversation(),
                    initialization.defaultWorkspace(),
                    initialization.ownerMember(),
                    owner,
                    initialization.ownerPersonalAgent(),
                    NOW);
            return new Fixture(
                    owner, initialization, conversation, session, true, true, true, true);
        }

        private Fixture withoutMember() {
            return new Fixture(
                    owner, initialization, conversation, session,
                    false, userParticipantPresent, workspacePresent, profilePresent);
        }

        private Fixture withoutUserParticipant() {
            return new Fixture(
                    owner, initialization, conversation, session,
                    memberPresent, false, workspacePresent, profilePresent);
        }

        private Fixture withoutWorkspace() {
            return new Fixture(
                    owner, initialization, conversation, session,
                    memberPresent, userParticipantPresent, false, profilePresent);
        }

        private Fixture withoutProfile() {
            return new Fixture(
                    owner, initialization, conversation, session,
                    memberPresent, userParticipantPresent, workspacePresent, false);
        }

        private PlatformExecutionContextResolutionRequest request(
                Map<ProviderType, ProviderBindingResolutionRequest> requirements) {
            return new PlatformExecutionContextResolutionRequest(
                    session,
                    owner.id(),
                    RuntimeInvocationId.generate(),
                    UUID.randomUUID(),
                    requirements);
        }

        private PlatformExecutionContextResolver resolver() {
            TimeProvider timeProvider = TimeProvider.from(Clock.fixed(
                    Instant.parse("2026-08-09T06:00:00Z"), ZoneOffset.UTC));
            ProviderBindingResolver bindingResolver = new ProviderBindingResolver(
                    proxy(ProviderBindingRepository.class, method -> switch (method) {
                        case "findById" -> Optional.empty();
                        case "findCandidates" -> List.of();
                        default -> null;
                    }),
                    proxy(ProviderDefinitionRepository.class, ignored -> Optional.empty()),
                    proxy(ProviderImplementationRepository.class, ignored -> Optional.empty()),
                    proxy(ConnectionRepository.class, ignored -> Optional.empty()),
                    proxy(ConnectionGrantRepository.class, ignored -> Optional.empty()),
                    timeProvider);
            return new PlatformExecutionContextResolver(
                    proxy(AgentRuntimeSessionRepository.class, method ->
                            "findById".equals(method) ? Optional.of(session) : session),
                    proxy(ConversationRepository.class, method ->
                            "findById".equals(method)
                                    ? Optional.of(conversation.conversation())
                                    : conversation.conversation()),
                    proxy(ConversationParticipantRepository.class, method ->
                            "findByConversation".equals(method)
                                    ? (userParticipantPresent
                                            ? List.of(
                                                    conversation.ownerParticipant(),
                                                    conversation.agentParticipant())
                                            : List.of(conversation.agentParticipant()))
                                    : null),
                    proxy(TeamRepository.class, method ->
                            "findById".equals(method)
                                    ? Optional.of(initialization.team())
                                    : initialization.team()),
                    proxy(WorkspaceRepository.class, method ->
                            "findById".equals(method)
                                    ? (workspacePresent
                                            ? Optional.of(initialization.defaultWorkspace())
                                            : Optional.empty())
                                    : initialization.defaultWorkspace()),
                    proxy(TeamMemberRepository.class, method ->
                            "findById".equals(method)
                                    ? (memberPresent
                                            ? Optional.of(initialization.ownerMember())
                                            : Optional.empty())
                                    : initialization.ownerMember()),
                    proxyWithArgs(PrincipalRepository.class, (method, arguments) -> {
                        if (!"findById".equals(method)) {
                            return Optional.empty();
                        }
                        PrincipalId principalId = (PrincipalId) arguments[1];
                        if (owner.id().equals(principalId)) {
                            return Optional.of(owner);
                        }
                        Principal agent = initialization.ownerPersonalAgent().agentPrincipal();
                        return agent.id().equals(principalId)
                                ? Optional.of(agent)
                                : Optional.empty();
                    }),
                    proxy(AgentProfileRepository.class, method ->
                            "findById".equals(method)
                                    ? (profilePresent
                                            ? Optional.of(initialization
                                                    .ownerPersonalAgent()
                                                    .agentProfile())
                                            : Optional.empty())
                                    : Optional.empty()),
                    proxy(MemberRoleRepository.class, method ->
                            "findByMember".equals(method)
                                    ? List.of(initialization.ownerRole())
                                    : List.of()),
                    proxy(TeamRoleRepository.class, method ->
                            "findByTeam".equals(method)
                                    ? initialization.builtInRoles()
                                    : List.of()),
                    bindingResolver,
                    timeProvider,
                    new ConversationVisibilityPolicy());
        }

    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Function<String, Object> handler) {
        return proxyWithArgs(type, (method, ignored) -> handler.apply(method));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxyWithArgs(
            Class<T> type, BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "TestProxy[" + type.getSimpleName() + "]";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return handler.apply(method.getName(), args);
                });
    }
}
