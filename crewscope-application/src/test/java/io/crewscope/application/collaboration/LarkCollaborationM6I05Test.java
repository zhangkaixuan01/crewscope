package io.crewscope.application.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.collaboration.LarkConnectionAuthorization;
import io.crewscope.domain.collaboration.LarkTenantKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M6-I05 administrator Preflight, health, revocation and safe evidence contract tests. */
class LarkCollaborationM6I05Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-26T09:00:00Z");
    private static final OrganizationId ORGANIZATION = id(OrganizationId::new);
    private static final TeamId TEAM = id(TeamId::new);
    private static final ProviderBindingId BINDING = id(ProviderBindingId::new);
    private static final Principal ADMIN = Principal.create(
            id(PrincipalId::new),
            PrincipalScope.team(ORGANIZATION, TEAM),
            PrincipalType.USER,
            Optional.empty(),
            "Lark Administrator",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            NOW);

    @Test
    void preflightReturnsOnlyCurrentSafeCoordinatesAndRequiresAdministrator() {
        MutableAuthorizationResolver resolver = new MutableAuthorizationResolver();
        RecordingAdministration administration = new RecordingAdministration();
        MutableHealthPort provider = new MutableHealthPort();
        LarkCollaborationApplicationService service = service(
                resolver, administration, provider);

        LarkConnectionPreflightResult result = service.preflight(command());

        assertEquals(BINDING, result.providerBindingId());
        assertEquals(4, result.providerBindingVersion());
        assertEquals(NOW, result.checkedAt());
        assertEquals(1, provider.calls);
        assertFalse((result + " " + provider.health).contains("tenant-a"));

        administration.allowed = false;
        assertThrows(DomainValidationException.class, () -> service.preflight(command()));
        assertEquals(1, provider.calls);
    }

    @Test
    void normalizesRateLimitAndFailsClosedAfterAuthorizationRevocation() {
        MutableAuthorizationResolver resolver = new MutableAuthorizationResolver();
        RecordingAdministration administration = new RecordingAdministration();
        MutableHealthPort provider = new MutableHealthPort();
        LarkCollaborationApplicationService service = service(
                resolver, administration, provider);
        provider.health = new LarkProviderHealth(
                LarkProviderHealthStatus.RATE_LIMITED,
                true,
                Optional.of(Duration.ofSeconds(7)),
                "LARK_RATE_LIMITED",
                NOW);

        LarkConnectionPreflightException failure = assertThrows(
                LarkConnectionPreflightException.class,
                () -> service.preflight(command()));
        assertEquals(LarkProviderHealthStatus.RATE_LIMITED, failure.health().status());

        resolver.available = false;
        LarkProviderHealth revoked = service.health(command());
        assertEquals(LarkProviderHealthStatus.AUTHORIZATION_UNAVAILABLE, revoked.status());
        assertEquals(1, provider.calls);
    }

    @Test
    void defaultAuthorizationResolverRequiresExactTeamOwnerScopeAndImplementation() {
        ProviderBindingResolver bindings = mock(ProviderBindingResolver.class);
        ProviderBinding binding = mock(ProviderBinding.class);
        ProviderDefinition definition = mock(ProviderDefinition.class);
        ProviderImplementation implementation = mock(ProviderImplementation.class);
        Connection connection = mock(Connection.class);
        ConnectionGrant grant = mock(ConnectionGrant.class);
        ProviderOwner teamOwner = new ProviderOwner(
                ORGANIZATION,
                ProviderOwnerType.TEAM,
                TEAM.value(),
                Optional.of(TEAM),
                Optional.empty());
        ProviderAccessScope access = new ProviderAccessScope(
                LarkCollaborationCapabilities.COMPLETE,
                ProviderResourceScope.allResources());
        ProviderBindingTarget target = new ProviderBindingTarget(
                ORGANIZATION,
                TEAM,
                WorkspaceId.generate(),
                ProviderBindingTargetType.WORKSPACE,
                Optional.empty());
        when(bindings.resolveCurrent(ORGANIZATION, BINDING)).thenReturn(Optional.of(
                new ProviderBindingCandidate(
                        binding,
                        definition,
                        implementation,
                        Optional.of(connection),
                        Optional.of(grant),
                        access)));
        when(binding.providerType()).thenReturn(ProviderType.COLLABORATION);
        when(binding.organizationId()).thenReturn(ORGANIZATION);
        when(binding.target()).thenReturn(target);
        when(binding.owner()).thenReturn(teamOwner);
        when(binding.id()).thenReturn(BINDING);
        when(binding.version()).thenReturn(4L);
        when(implementation.type()).thenReturn(ProviderType.COLLABORATION);
        when(implementation.key()).thenReturn("lark-collaboration");
        when(connection.connectorKey()).thenReturn("lark-collaboration");
        when(connection.externalAccountReference()).thenReturn("tenant-a");
        when(connection.id()).thenReturn(id(ConnectionId::new));
        when(connection.version()).thenReturn(5L);
        when(grant.grantee()).thenReturn(teamOwner);
        when(grant.id()).thenReturn(id(ConnectionGrantId::new));
        when(grant.version()).thenReturn(6L);
        DefaultLarkConnectionAuthorizationResolver resolver =
                new DefaultLarkConnectionAuthorizationResolver(bindings);

        LarkConnectionAuthorization resolved = resolver.resolveCurrent(
                ORGANIZATION,
                TEAM,
                BINDING,
                LarkCollaborationCapabilities.MEMBER_MAPPING);
        assertEquals(4, resolved.providerBindingVersion());
        assertEquals(5, resolved.connectionVersion());

        TeamId otherTeam = id(TeamId::new);
        assertThrows(DomainValidationException.class, () -> resolver.resolveCurrent(
                ORGANIZATION,
                otherTeam,
                BINDING,
                LarkCollaborationCapabilities.MEMBER_MAPPING));
        when(implementation.key()).thenReturn("different-collaboration");
        assertThrows(DomainValidationException.class, () -> resolver.resolveCurrent(
                ORGANIZATION,
                TEAM,
                BINDING,
                LarkCollaborationCapabilities.MEMBER_MAPPING));
    }

    @Test
    void defaultAdministrationRequiresCurrentMembershipAndProviderManageGrant() {
        TeamMemberRepository members = mock(TeamMemberRepository.class);
        TeamRoleRepository roles = mock(TeamRoleRepository.class);
        MemberRoleRepository grants = mock(MemberRoleRepository.class);
        TeamMember member = mock(TeamMember.class);
        TeamRole role = mock(TeamRole.class);
        MemberRole grant = mock(MemberRole.class);
        TeamMemberId memberId = id(TeamMemberId::new);
        TeamRoleId roleId = id(TeamRoleId::new);
        when(members.findByTeamAndUserPrincipalId(ORGANIZATION, TEAM, ADMIN.id()))
                .thenReturn(Optional.of(member));
        when(member.canParticipate()).thenReturn(true);
        when(member.id()).thenReturn(memberId);
        when(roles.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(role));
        when(role.id()).thenReturn(roleId);
        when(role.isGrantable()).thenReturn(true);
        when(role.permissions()).thenReturn(Set.of(TeamPermission.PROVIDER_MANAGE));
        when(grants.findByMember(ORGANIZATION, memberId)).thenReturn(List.of(grant));
        when(grant.status()).thenReturn(MemberRoleStatus.ACTIVE);
        when(grant.isEffectiveAt(NOW)).thenReturn(true);
        when(grant.roleScope()).thenReturn(RoleScope.team());
        when(grant.teamRoleId()).thenReturn(roleId);
        DefaultLarkMappingAdministration administration =
                new DefaultLarkMappingAdministration(members, roles, grants);

        administration.requireProviderAdministrator(ORGANIZATION, TEAM, ADMIN, NOW);

        when(role.permissions()).thenReturn(Set.of(TeamPermission.TEAM_OBSERVE));
        assertThrows(PolicyDeniedException.class, () -> administration
                .requireProviderAdministrator(ORGANIZATION, TEAM, ADMIN, NOW));
    }

    private static LarkConnectionPreflightCommand command() {
        return new LarkConnectionPreflightCommand(
                ORGANIZATION,
                TEAM,
                BINDING,
                LarkCollaborationCapabilities.MEMBER_MAPPING,
                ADMIN);
    }

    private static LarkCollaborationApplicationService service(
            LarkConnectionAuthorizationResolver resolver,
            LarkMappingAdministration administration,
            LarkProviderHealthPort provider) {
        return new LarkCollaborationApplicationService(
                resolver,
                administration,
                provider,
                TimeProvider.from(Clock.fixed(
                        Instant.parse("2026-08-26T09:00:00Z"), ZoneOffset.UTC)));
    }

    private static final class MutableAuthorizationResolver
            implements LarkConnectionAuthorizationResolver {
        private boolean available = true;

        @Override
        public LarkConnectionAuthorization resolveCurrent(
                OrganizationId organizationId,
                TeamId teamId,
                ProviderBindingId providerBindingId,
                io.crewscope.domain.provider.ProviderCapabilities requiredCapabilities) {
            if (!available
                    || !ORGANIZATION.equals(organizationId)
                    || !TEAM.equals(teamId)
                    || !BINDING.equals(providerBindingId)) {
                throw new DomainValidationException(
                        "larkConnectionAuthorization", "is unavailable");
            }
            return new LarkConnectionAuthorization(
                    ORGANIZATION,
                    TEAM,
                    BINDING,
                    4,
                    id(ConnectionId::new),
                    5,
                    id(ConnectionGrantId::new),
                    6,
                    new LarkTenantKey("tenant-a"),
                    LarkCollaborationCapabilities.COMPLETE).requireCapabilities(
                            requiredCapabilities);
        }
    }

    private static final class RecordingAdministration implements LarkMappingAdministration {
        private boolean allowed = true;

        @Override
        public void requireProviderAdministrator(
                OrganizationId organizationId,
                TeamId teamId,
                Principal actor,
                UtcTimestamp occurredAt) {
            if (!allowed) {
                throw new DomainValidationException(
                        "larkMapping.administrator", "requires PROVIDER_MANAGE");
            }
        }
    }

    private static final class MutableHealthPort implements LarkProviderHealthPort {
        private LarkProviderHealth health = LarkProviderHealth.healthy(NOW);
        private int calls;

        @Override
        public LarkProviderHealth checkHealth(
                LarkConnectionAuthorization authorization, PrincipalId actor) {
            calls++;
            return health;
        }
    }

    private static <T> T id(java.util.function.Function<UUID, T> factory) {
        return factory.apply(UUID.randomUUID());
    }
}
