package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M5-A01 owner authorization, server-owned fields and safe query contract tests. */
class ModelConnectionApplicationServiceM5A01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T09:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final ModelProviderKey providerKey = new ModelProviderKey("deepseek");
    private final ModelRegion region = new ModelRegion("global");
    private final ModelProviderDefinition provider = ModelProviderDefinition.publish(
            providerKey,
            "DeepSeek",
            new ModelAdapterKey("deepseek"),
            new ModelEndpoint("https://private-provider.example/v1"),
            java.util.Set.of(region),
            ModelDataPolicy.noRetention(),
            actor.id(),
            NOW);

    private ModelProviderDefinitionRepository providers;
    private ModelCatalogEntryRepository catalogs;
    private ModelPriceScheduleRepository prices;
    private ModelConnectionRepository connections;
    private ModelConnectionCredentialService credentials;
    private CommandReceiptStore receipts;
    private TeamRepository teams;
    private TeamMembershipQuery memberships;
    private TeamRoleRepository roles;
    private MemberRoleRepository grants;
    private ModelConnectionApplicationService service;

    @BeforeEach
    void setUp() {
        providers = mock(ModelProviderDefinitionRepository.class);
        catalogs = mock(ModelCatalogEntryRepository.class);
        prices = mock(ModelPriceScheduleRepository.class);
        connections = mock(ModelConnectionRepository.class);
        credentials = mock(ModelConnectionCredentialService.class);
        receipts = mock(CommandReceiptStore.class);
        teams = mock(TeamRepository.class);
        memberships = mock(TeamMembershipQuery.class);
        roles = mock(TeamRoleRepository.class);
        grants = mock(MemberRoleRepository.class);
        when(providers.findByKey(providerKey)).thenReturn(Optional.of(provider));
        service = new ModelConnectionApplicationService(
                providers,
                catalogs,
                prices,
                connections,
                credentials,
                receipts,
                teams,
                memberships,
                roles,
                grants,
                () -> NOW);
    }

    @Test
    void createsAUserConnectionWithServerOwnedEndpointCredentialAndBillingSubjects() {
        CommandReceipt receipt = receipt(0);
        when(credentials.create(any(), any(), any()))
                .thenReturn(CommandExecution.completed(mock(ModelConnection.class), receipt));
        CredentialSecret secret = CredentialSecret.utf8("secret-value");

        CommandExecution<ModelConnection> result = service.create(
                commandContext(false),
                new CreateModelConnectionRequest(
                        providerKey,
                        ModelConnectionOwnerType.USER,
                        Optional.empty(),
                        region,
                        Optional.empty()),
                secret);

        assertEquals(receipt, result.receipt());
        assertTrue(secret.isClosed());
        ArgumentCaptor<CreateModelConnectionCredentialCommand> command =
                ArgumentCaptor.forClass(CreateModelConnectionCredentialCommand.class);
        verify(credentials).create(command.capture(), any(), any());
        assertEquals(provider.defaultEndpoint(), command.getValue().endpoint());
        assertEquals(actor.id(), command.getValue().owner().userPrincipalId().orElseThrow());
        assertEquals(actor.id(), command.getValue().billingSubject().principalId().orElseThrow());
        assertEquals(actor.id(), command.getValue().credentialSubject().principalId().orElseThrow());
        assertFalse(command.getValue().credentialMetadata().toString().contains("secret-value"));
    }

    @Test
    void deniesTeamConnectionManagementWithoutProviderManage() {
        TeamInitialization initialization = TeamInitialization.create(actor, "Team", NOW);
        when(teams.findUninitializedById(organizationId, initialization.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, initialization.team().id()))
                .thenReturn(Optional.of(initialization.team()));
        when(memberships.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(List.of(initialization.ownerMember()));
        when(roles.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(initialization.builtInRoles());
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of());
        CredentialSecret secret = CredentialSecret.utf8("team-secret");

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(
                        commandContext(false),
                        new CreateModelConnectionRequest(
                                providerKey,
                                ModelConnectionOwnerType.TEAM,
                                Optional.of(initialization.team().id()),
                                region,
                                Optional.empty()),
                        secret));

        assertTrue(secret.isClosed());
        verify(credentials, never()).create(any(), any(), any());
    }

    @Test
    void allowsThePlatformAdministratorToCreateOrganizationConnectionsOnly() {
        when(credentials.create(any(), any(), any()))
                .thenReturn(CommandExecution.completed(mock(ModelConnection.class), receipt(0)));
        CreateModelConnectionRequest request = new CreateModelConnectionRequest(
                providerKey,
                ModelConnectionOwnerType.ORGANIZATION,
                Optional.empty(),
                region,
                Optional.empty());

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(
                        commandContext(false), request, CredentialSecret.utf8("org-secret")));
        service.create(commandContext(true), request, CredentialSecret.utf8("org-secret"));

        verify(credentials).create(any(), any(), any());
    }

    @Test
    void listsOnlyTheAuthenticatedUsersExactOwnerBoundary() {
        ModelConnection connection = userConnection(actor);
        ModelConnectionOwner owner = ModelConnectionOwner.user(actor);
        when(connections.findByOwner(owner, 0, 20)).thenReturn(List.of(connection));

        List<ModelConnection> result = service.listConnections(
                access(false),
                organizationId,
                ModelConnectionOwnerType.USER,
                Optional.empty(),
                0,
                20);

        assertEquals(List.of(connection), result);
        verify(connections).findByOwner(owner, 0, 20);
    }

    @Test
    void preventsOneUserFromReadingAnotherUsersConnection() {
        Principal other = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Other",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        ModelConnection connection = userConnection(other);
        when(connections.findById(organizationId, connection.id())).thenReturn(Optional.of(connection));

        assertThrows(
                PolicyDeniedException.class,
                () -> service.getConnection(access(false), organizationId, connection.id()));
    }

    @Test
    void returnsProviderAndCatalogDataOnlyAfterOrganizationIdentityValidation() {
        when(providers.findPage(0, 20)).thenReturn(List.of(provider));

        assertEquals(
                List.of(provider), service.listProviders(access(false), organizationId, 0, 20));

        OrganizationId otherOrganization = OrganizationId.generate();
        assertThrows(
                PolicyDeniedException.class,
                () -> service.listProviders(access(false), otherOrganization, 0, 20));
    }

    private TeamAccessContext access(boolean administrator) {
        return new TeamAccessContext(actor, administrator);
    }

    private TeamCommandContext commandContext(boolean administrator) {
        return new TeamCommandContext(
                access(administrator),
                IdempotencyKey.from("m5-a01-" + UUID.randomUUID()),
                UUID.randomUUID(),
                Optional.empty());
    }

    private ModelConnection userConnection(Principal owner) {
        return ModelConnection.open(
                provider,
                ModelConnectionId.generate(),
                ModelConnectionOwner.user(owner),
                provider.defaultEndpoint(),
                region,
                new ModelCredentialBinding(
                        CredentialId.generate(),
                        ModelCredentialSubject.principal(organizationId, owner.id()),
                        new ModelCredentialVersion(0)),
                ModelBillingSubject.principal(organizationId, owner.id()),
                owner.id(),
                NOW);
    }

    private static CommandReceipt receipt(long version) {
        return new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
    }
}
