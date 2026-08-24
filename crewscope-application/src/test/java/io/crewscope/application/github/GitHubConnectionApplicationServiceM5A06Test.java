package io.crewscope.application.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M5-A06 Connection ownership, policy reconstruction and trusted Preflight tests. */
class GitHubConnectionApplicationServiceM5A06Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T12:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            io.crewscope.domain.shared.id.PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "GitHub Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private ConnectionRepository connections;
    private ConnectionGrantRepository grants;
    private CredentialStore credentials;
    private GitHubProviderRepository githubRepository;
    private GitHubProviderPort provider;
    private TeamRepository teams;
    private TeamMembershipQuery memberships;
    private TeamRoleRepository roles;
    private MemberRoleRepository memberRoles;
    private ProviderBindingRepository bindings;
    private ProviderDefinitionRepository definitions;
    private ProviderImplementationRepository implementations;
    private WorkspaceRepository workspaces;
    private DomainEventStore eventStore;
    private OutboxRepository outbox;
    private CommandReceiptStore receipts;
    private GitHubConnectionApplicationService service;

    @BeforeEach
    void setUp() {
        connections = mock(ConnectionRepository.class);
        grants = mock(ConnectionGrantRepository.class);
        credentials = mock(CredentialStore.class);
        githubRepository = mock(GitHubProviderRepository.class);
        provider = mock(GitHubProviderPort.class);
        teams = mock(TeamRepository.class);
        memberships = mock(TeamMembershipQuery.class);
        roles = mock(TeamRoleRepository.class);
        memberRoles = mock(MemberRoleRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        definitions = mock(ProviderDefinitionRepository.class);
        implementations = mock(ProviderImplementationRepository.class);
        bindings = mock(ProviderBindingRepository.class);
        ProviderBootstrapLock bootstrapLock = mock(ProviderBootstrapLock.class);
        eventStore = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        receipts = mock(CommandReceiptStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(connections.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CredentialDescriptor descriptor = mock(CredentialDescriptor.class);
        when(descriptor.status()).thenReturn(CredentialStatus.ACTIVE);
        when(credentials.create(any(), any())).thenReturn(descriptor);
        when(credentials.describe(any(), any())).thenReturn(Optional.of(descriptor));
        BuiltInProviderRegistration registration = new BuiltInProviderRegistration(
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                ProviderType.SOURCE_CODE,
                "1.0.0",
                "GitHub",
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                "1.0.0",
                GitHubConnectionApplicationService.DELIVERY_CAPABILITIES);
        service = new GitHubConnectionApplicationService(
                connections,
                grants,
                credentials,
                githubRepository,
                provider,
                teams,
                memberships,
                roles,
                memberRoles,
                workspaces,
                definitions,
                implementations,
                bindings,
                bootstrapLock,
                registration,
                eventStore,
                outbox,
                receipts,
                transactions,
                () -> NOW,
                new GitHubConnectionPolicySettings(Set.of("crewscope"), true, false, true, false));
    }

    @Test
    void createsUserOauthWithPrincipalCredentialAndExplicitRepositoryGrant() {
        CredentialSecret secret = CredentialSecret.utf8("oauth-token-never-returned");

        CommandExecution<GitHubConnectionView> execution = service.create(
                commandContext(false),
                organizationId,
                new CreateGitHubConnectionRequest(
                        GitHubAuthenticationType.OAUTH_USER,
                        Optional.empty(),
                        CredentialSubjectType.PRINCIPAL,
                        "2718",
                        Set.of("CrewScope/Repository-A"),
                        Optional.empty()),
                secret);

        GitHubConnectionView result = execution.result().orElseThrow();
        assertTrue(secret.isClosed());
        assertEquals("USER", result.ownerType().name());
        assertEquals(List.of("crewscope/repository-a"), result.repositoryAllowlist());
        ArgumentCaptor<CredentialCreateRequest> credential =
                ArgumentCaptor.forClass(CredentialCreateRequest.class);
        verify(credentials).create(credential.capture(), any());
        assertEquals(CredentialSubjectType.PRINCIPAL, credential.getValue().subject().type());
        assertEquals(actor.id(), credential.getValue().subject().principalId().orElseThrow());
        assertEquals(Set.of("authenticationType", "ownerType"),
                credential.getValue().metadata().keySet());
        ArgumentCaptor<ConnectionGrant> grant = ArgumentCaptor.forClass(ConnectionGrant.class);
        verify(grants).create(grant.capture());
        assertEquals(Set.of("github:repository:crewscope/repository-a"),
                grant.getValue().grantedAccess().resources().resources());
        verify(eventStore).append(any());
        verify(outbox).enqueue(any());
        verify(receipts).complete(any(), any(), any(), any());
    }

    @Test
    void closesSecretAndDeniesTeamAppWithoutProviderManage() {
        TeamInitialization team = TeamInitialization.create(actor, "Delivery Team", NOW);
        when(teams.findUninitializedById(organizationId, team.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, team.team().id()))
                .thenReturn(Optional.of(team.team()));
        when(memberships.findByTeam(organizationId, team.team().id()))
                .thenReturn(List.of(team.ownerMember()));
        when(roles.findByTeam(organizationId, team.team().id()))
                .thenReturn(team.builtInRoles());
        when(memberRoles.findByMember(organizationId, team.ownerMember().id()))
                .thenReturn(List.of());
        CredentialSecret secret = CredentialSecret.utf8("app-token");

        assertThrows(PolicyDeniedException.class, () -> service.create(
                commandContext(false),
                organizationId,
                new CreateGitHubConnectionRequest(
                        GitHubAuthenticationType.APP_INSTALLATION,
                        Optional.of(team.team().id()),
                        CredentialSubjectType.TEAM,
                        "4815",
                        Set.of("crewscope/repository-a"),
                        Optional.empty()),
                secret));

        assertTrue(secret.isClosed());
        verify(credentials, never()).create(any(), any());
    }

    @Test
    void exactCreateReplayReturnsReceiptWithoutReusingTheSecret() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(receipt));
        CredentialSecret secret = CredentialSecret.utf8("replayed-token");

        CommandExecution<GitHubConnectionView> replay = service.create(
                commandContext(false),
                organizationId,
                new CreateGitHubConnectionRequest(
                        GitHubAuthenticationType.OAUTH_USER,
                        Optional.empty(),
                        CredentialSubjectType.PRINCIPAL,
                        "2718",
                        Set.of("crewscope/repository-a"),
                        Optional.empty()),
                secret);

        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        assertTrue(secret.isClosed());
        verify(credentials, never()).create(any(), any());
        verify(eventStore, never()).append(any());
    }

    @Test
    void remotePreflightUsesPersistedBindingGrantAndDefaultBranchFacts() {
        TeamInitialization team = TeamInitialization.create(actor, "Coding Team", NOW);
        when(teams.findUninitializedById(organizationId, team.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, team.team().id()))
                .thenReturn(Optional.of(team.team()));
        when(memberships.findByTeam(organizationId, team.team().id()))
                .thenReturn(List.of(team.ownerMember()));
        Connection connection = Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.user(actor),
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                "2718",
                io.crewscope.domain.shared.id.CredentialId.generate(),
                Optional.empty(),
                actor,
                NOW);
        ConnectionGrant grant = ConnectionGrant.grant(
                io.crewscope.domain.provider.ConnectionGrantId.generate(),
                connection,
                connection.owner(),
                new io.crewscope.domain.provider.ProviderAccessScope(
                        GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                        io.crewscope.domain.provider.ProviderResourceScope.of(
                                "github:repository:crewscope/repository-a")),
                NOW,
                Optional.empty(),
                actor,
                NOW);
        when(connections.findById(organizationId, connection.id())).thenReturn(Optional.of(connection));
        when(grants.findByConnectionAndGrantee(connection.id(), connection.owner()))
                .thenReturn(List.of(grant));
        ProviderBindingId bindingId = ProviderBindingId.generate();
        ProviderBinding binding = mock(ProviderBinding.class);
        when(binding.connectionId()).thenReturn(Optional.of(connection.id()));
        when(binding.connectionVersion()).thenReturn(Optional.of(connection.version()));
        when(binding.connectionGrantId()).thenReturn(Optional.of(grant.id()));
        when(binding.connectionGrantVersion()).thenReturn(Optional.of(grant.version()));
        when(binding.owner()).thenReturn(connection.owner());
        when(binding.providerType()).thenReturn(ProviderType.SOURCE_CODE);
        when(binding.target()).thenReturn(new ProviderBindingTarget(
                organizationId,
                team.team().id(),
                WorkspaceId.generate(),
                io.crewscope.domain.provider.ProviderBindingTargetType.WORKSPACE,
                Optional.empty()));
        when(binding.effectiveAccess()).thenReturn(grant.grantedAccess());
        when(binding.status()).thenReturn(ProviderRegistrationStatus.ACTIVE);
        when(binding.executionIdentity()).thenReturn(Optional.of(ProviderExecutionIdentity.DELEGATED_USER));
        ProviderDefinitionId definitionId = new ProviderDefinitionId(UUID.randomUUID());
        ProviderImplementationId implementationId = new ProviderImplementationId(UUID.randomUUID());
        ProviderDefinition definition = mock(ProviderDefinition.class);
        ProviderImplementation implementation = mock(ProviderImplementation.class);
        when(binding.definitionId()).thenReturn(definitionId);
        when(binding.implementationId()).thenReturn(implementationId);
        when(definitions.findById(organizationId, definitionId)).thenReturn(Optional.of(definition));
        when(implementations.findById(organizationId, implementationId))
                .thenReturn(Optional.of(implementation));
        when(binding.currentAccess(
                        definition,
                        implementation,
                        Optional.of(connection),
                        Optional.of(grant),
                        NOW))
                .thenReturn(Optional.of(grant.grantedAccess()));
        when(bindings.findById(organizationId, bindingId)).thenReturn(Optional.of(binding));
        GitHubRepositoryCatalogEntry catalog = mock(GitHubRepositoryCatalogEntry.class);
        when(catalog.connectionVersion()).thenReturn(connection.version());
        when(catalog.externalRepositoryId()).thenReturn("12345");
        when(catalog.defaultBranch()).thenReturn(new RepositoryBranchName("main"));
        when(githubRepository.findRepository(organizationId, connection.id(), "12345"))
                .thenReturn(Optional.of(catalog));
        when(provider.preflightRepository(any())).thenReturn(new GitHubRepositoryPreflightResult(
                connection.id(),
                connection.version(),
                grant.id(),
                grant.version(),
                "12345",
                "crewscope/repository-a",
                new RepositoryBranchName("main"),
                GitHubHash.sha256("permissions")));

        service.preflightRepository(
                access(false),
                organizationId,
                connection.id(),
                connection.version(),
                bindingId,
                "12345",
                UUID.randomUUID());

        ArgumentCaptor<PreflightGitHubRepositoryRequest> request =
                ArgumentCaptor.forClass(PreflightGitHubRepositoryRequest.class);
        verify(provider).preflightRepository(request.capture());
        assertEquals(binding.effectiveAccess(), request.getValue().access().requestedAccess());
        assertEquals(grant.id(), request.getValue().access().connectionGrantId());
        assertEquals(new RepositoryBranchName("main"), request.getValue().expectedDefaultBranch());
        assertEquals(Set.of("crewscope/repository-a"),
                request.getValue().repositoryPolicy().repositoryAllowlist());
    }

    @Test
    void bindsOnlyAVerifiedConnectionToTheSelectedTeamWorkspace() {
        TeamInitialization team = TeamInitialization.create(actor, "Binding Team", NOW);
        when(teams.findUninitializedById(organizationId, team.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, team.team().id()))
                .thenReturn(Optional.of(team.team()));
        when(memberships.findByTeam(organizationId, team.team().id()))
                .thenReturn(List.of(team.ownerMember()));
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.user(actor),
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                "2718",
                io.crewscope.domain.shared.id.CredentialId.generate(),
                Optional.empty(),
                actor,
                NOW);
        ConnectionGrant grant = ConnectionGrant.grant(
                io.crewscope.domain.provider.ConnectionGrantId.generate(),
                connection,
                connection.owner(),
                new io.crewscope.domain.provider.ProviderAccessScope(
                        GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                        io.crewscope.domain.provider.ProviderResourceScope.of(
                                "github:repository:crewscope/repository-a")),
                NOW,
                Optional.empty(),
                actor,
                NOW);
        when(connections.findById(organizationId, connection.id()))
                .thenReturn(Optional.of(connection));
        when(grants.findByConnectionAndGrantee(connection.id(), connection.owner()))
                .thenReturn(List.of(grant));
        GitHubConnectionProfile profile = mock(GitHubConnectionProfile.class);
        when(profile.isCurrentFor(connection.version())).thenReturn(true);
        when(githubRepository.findProfile(organizationId, connection.id(), connection.version()))
                .thenReturn(Optional.of(profile));
        when(definitions.findByKey(
                        organizationId, GitHubConnectionApplicationService.CONNECTOR_KEY))
                .thenReturn(Optional.empty());
        when(definitions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(implementations.findByDefinition(any(), any())).thenReturn(List.of());
        when(implementations.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindings.findCandidates(any())).thenReturn(List.of());
        when(bindings.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CommandExecution<GitHubProviderBindingView> execution = service.bind(
                commandContext(false),
                organizationId,
                connection.id(),
                connection.version(),
                team.team().id(),
                false);

        GitHubProviderBindingView result = execution.result().orElseThrow();
        assertEquals(team.team().id(), result.teamId());
        assertEquals(team.defaultWorkspace().id(), result.workspaceId());
        assertEquals(connection.id().toString(), result.connectionId());
        assertEquals(List.of("crewscope/repository-a"), result.repositoryAllowlist());
        ArgumentCaptor<ProviderBinding> binding = ArgumentCaptor.forClass(ProviderBinding.class);
        verify(bindings).create(binding.capture());
        assertEquals(grant.id(), binding.getValue().connectionGrantId().orElseThrow());
        assertEquals(ProviderExecutionIdentity.DELEGATED_USER,
                binding.getValue().executionIdentity().orElseThrow());
    }

    @Test
    void revokesGrantCredentialAndConnectionOnceThenReplaysTheReceipt() {
        Connection initial = Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.user(actor),
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                "2718",
                io.crewscope.domain.shared.id.CredentialId.generate(),
                Optional.empty(),
                actor,
                NOW);
        ConnectionGrant grant = ConnectionGrant.grant(
                io.crewscope.domain.provider.ConnectionGrantId.generate(),
                initial,
                initial.owner(),
                new io.crewscope.domain.provider.ProviderAccessScope(
                        GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                        io.crewscope.domain.provider.ProviderResourceScope.of(
                                "github:repository:crewscope/repository-a")),
                NOW,
                Optional.empty(),
                actor,
                NOW);
        AtomicReference<Connection> storedConnection = new AtomicReference<>(initial);
        when(connections.findById(organizationId, initial.id()))
                .thenAnswer(ignored -> Optional.of(storedConnection.get()));
        when(connections.update(any())).thenAnswer(invocation -> {
            Connection updated = invocation.getArgument(0);
            storedConnection.set(updated);
            return updated;
        });
        when(grants.findByConnectionAndGrantee(initial.id(), initial.owner()))
                .thenReturn(List.of(grant));
        when(grants.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CredentialDescriptor credential = mock(CredentialDescriptor.class);
        CredentialReference credentialReference =
                new CredentialReference(organizationId, initial.credentialId());
        when(credential.reference()).thenReturn(credentialReference);
        when(credential.version()).thenReturn(3L);
        when(credential.status()).thenReturn(CredentialStatus.ACTIVE);
        when(credentials.describe(any(), any())).thenReturn(Optional.of(credential));
        when(credentials.revoke(any(), any(Long.class), any(), any()))
                .thenReturn(credential);
        CommandReceipt replayReceipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(receipts.reserve(any()))
                .thenReturn(CommandReservation.newlyAcquired(), CommandReservation.replay(replayReceipt));
        TeamCommandContext command = commandContext(false);

        CommandExecution<GitHubConnectionView> completed = service.revoke(
                command, organizationId, initial.id(), initial.version(), "access removed");
        CommandExecution<GitHubConnectionView> replayed = service.revoke(
                command, organizationId, initial.id(), initial.version(), "access removed");

        assertEquals(ConnectionStatus.REVOKED,
                completed.result().orElseThrow().status());
        assertTrue(replayed.replayed());
        assertEquals(replayReceipt, replayed.receipt());
        ArgumentCaptor<ConnectionGrant> revokedGrant =
                ArgumentCaptor.forClass(ConnectionGrant.class);
        verify(grants).update(revokedGrant.capture());
        assertEquals(ConnectionGrantStatus.REVOKED, revokedGrant.getValue().status());
        ArgumentCaptor<Connection> revokedConnection = ArgumentCaptor.forClass(Connection.class);
        verify(connections).update(revokedConnection.capture());
        assertEquals(ConnectionStatus.REVOKED, revokedConnection.getValue().status());
        verify(credentials).revoke(
                credentialReference,
                credential.version(),
                new io.crewscope.application.credential.CredentialMutationContext(
                        organizationId, actor.id()),
                io.crewscope.application.credential.CredentialRevocationReason.CONNECTION_REVOKED);
        verify(eventStore, times(1)).append(any());
        verify(outbox, times(1)).enqueue(any());
        verify(receipts, times(1)).complete(any(), any(), any(), any());
    }

    private TeamAccessContext access(boolean administrator) {
        return new TeamAccessContext(actor, administrator);
    }

    private TeamCommandContext commandContext(boolean administrator) {
        return new TeamCommandContext(
                access(administrator),
                IdempotencyKey.from("m5-a06-" + UUID.randomUUID()),
                UUID.randomUUID(),
                Optional.empty());
    }
}
