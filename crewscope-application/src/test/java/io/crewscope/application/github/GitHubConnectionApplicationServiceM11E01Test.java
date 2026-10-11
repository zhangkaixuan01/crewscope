package io.crewscope.application.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
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
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.provider.event.ConnectionLifecycleChanged;
import io.crewscope.domain.provider.event.ProviderBindingChanged;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.UninitializedTeam;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M11-E01 characterization suite for {@link GitHubConnectionApplicationService}: locks the
 * authorization, command-journal, provider-bootstrap, access-fact and read-model contracts
 * before the service is decomposed into collaborating components. All tests run against the
 * current 21-dependency implementation and must keep passing unmodified afterwards.
 */
class GitHubConnectionApplicationServiceM11E01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T12:00:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-24T13:00:00Z");
    private static final UtcTimestamp EARLIER = UtcTimestamp.parse("2026-08-24T11:00:00Z");
    private static final String KEY = GitHubConnectionApplicationService.CONNECTOR_KEY;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
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
    private WorkspaceRepository workspaces;
    private ProviderDefinitionRepository definitions;
    private ProviderImplementationRepository implementations;
    private ProviderBindingRepository bindings;
    private ProviderBootstrapLock bootstrapLock;
    private DomainEventStore eventStore;
    private OutboxRepository outbox;
    private CommandReceiptStore receipts;
    private BuiltInProviderRegistration registration;
    private CredentialDescriptor descriptor;

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
        bootstrapLock = mock(ProviderBootstrapLock.class);
        eventStore = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        receipts = mock(CommandReceiptStore.class);
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(connections.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(connections.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        descriptor = mock(CredentialDescriptor.class);
        when(descriptor.status()).thenReturn(CredentialStatus.ACTIVE);
        when(descriptor.isUsableAt(any())).thenReturn(true);
        when(credentials.create(any(), any())).thenReturn(descriptor);
        when(credentials.describe(any(), any())).thenReturn(Optional.of(descriptor));
        registration = new BuiltInProviderRegistration(
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                ProviderType.SOURCE_CODE,
                "1.0.0",
                "GitHub",
                GitHubConnectionApplicationService.CONNECTOR_KEY,
                "1.0.0",
                GitHubConnectionApplicationService.DELIVERY_CAPABILITIES);
    }

    private GitHubConnectionApplicationService service(boolean webhookReceiverConfigured) {
        return new GitHubConnectionApplicationService(
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
                transactions(),
                () -> NOW,
                new GitHubConnectionPolicySettings(
                        Set.of("crewscope"), true, false, true, webhookReceiverConfigured));
    }

    private GitHubConnectionApplicationService service() {
        return service(true);
    }

    private TransactionExecutor transactions() {
        TransactionExecutor executor = mock(TransactionExecutor.class);
        when(executor.required(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        return executor;
    }

    private TeamAccessContext access(boolean administrator) {
        return new TeamAccessContext(actor, administrator);
    }

    private TeamCommandContext commandContext(boolean administrator) {
        return new TeamCommandContext(
                access(administrator),
                IdempotencyKey.from("m11-e01-" + UUID.randomUUID()),
                UUID.randomUUID(),
                Optional.empty());
    }

    private void stubTeamReady(TeamInitialization team) {
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
    }

    private Connection userConnection(String externalReference, UtcTimestamp occurredAt) {
        return Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.user(actor),
                KEY,
                externalReference,
                CredentialId.generate(),
                Optional.empty(),
                actor,
                occurredAt);
    }

    private Connection teamConnection(Team team, UtcTimestamp occurredAt) {
        return Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.team(team),
                KEY,
                "4815",
                CredentialId.generate(),
                Optional.empty(),
                actor,
                occurredAt);
    }

    private ConnectionGrant explicitGrant(Connection connection, String... resources) {
        return ConnectionGrant.grant(
                ConnectionGrantId.generate(),
                connection,
                connection.owner(),
                new ProviderAccessScope(
                        GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                        io.crewscope.domain.provider.ProviderResourceScope.of(resources)),
                NOW,
                Optional.empty(),
                actor,
                NOW);
    }

    private ConnectionGrant discoveryGrant(Connection connection) {
        return ConnectionGrant.grant(
                ConnectionGrantId.generate(),
                connection,
                connection.owner(),
                new ProviderAccessScope(
                        GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                        io.crewscope.domain.provider.ProviderResourceScope.allResources()),
                NOW,
                Optional.empty(),
                actor,
                NOW);
    }

    private void stubStored(Connection connection, ConnectionGrant grant) {
        when(connections.findById(organizationId, connection.id()))
                .thenReturn(Optional.of(connection));
        when(grants.findByConnectionAndGrantee(connection.id(), connection.owner()))
                .thenReturn(grant == null ? List.of() : List.of(grant));
    }

    private void stubVerified(Connection connection) {
        GitHubConnectionProfile profile = mock(GitHubConnectionProfile.class);
        when(profile.isCurrentFor(connection.version())).thenReturn(true);
        when(githubRepository.findProfile(organizationId, connection.id(), connection.version()))
                .thenReturn(Optional.of(profile));
    }

    private void stubFoundation() {
        when(definitions.findByKey(organizationId, KEY)).thenReturn(Optional.empty());
        when(definitions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(implementations.findByDefinition(any(), any())).thenReturn(List.of());
        when(implementations.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindings.findCandidates(any())).thenReturn(List.of());
        when(bindings.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private GitHubRepositoryCatalogEntry catalogEntry(
            Connection connection,
            String ownerLogin,
            String repositoryName,
            long connectionVersion,
            UtcTimestamp discoveredAt,
            UtcTimestamp cacheExpiresAt,
            GitHubRepositoryStatus status) {
        return new GitHubRepositoryCatalogEntry(
                UUID.randomUUID(),
                organizationId,
                connection.id(),
                connectionVersion,
                ProviderExecutionIdentity.DELEGATED_USER,
                "42",
                ownerLogin,
                repositoryName,
                ownerLogin + "/" + repositoryName,
                new RepositoryBranchName("main"),
                GitHubRepositoryVisibility.PUBLIC,
                false,
                false,
                true,
                true,
                true,
                GitHubHash.sha256("permissions"),
                Optional.empty(),
                discoveredAt,
                cacheExpiresAt,
                status,
                0,
                AuditMetadata.createdBy(actor.id(), discoveredAt));
    }

    // ------------------------------------------------------------------
    // Authorization block: who is allowed to act, in which order.
    // ------------------------------------------------------------------

    @Test
    void listsUserConnectionsFilteredByConnectorKeyNewestFirstWithinTheRequestedPage() {
        Connection newest = userConnection("2718", NOW);
        Connection oldest = userConnection("2719", EARLIER);
        Connection foreignConnector = Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.user(actor),
                "lark-calendar",
                "3141",
                CredentialId.generate(),
                Optional.empty(),
                actor,
                LATER);
        when(connections.findByOwner(any()))
                .thenReturn(List.of(oldest, foreignConnector, newest));

        List<GitHubConnectionView> page =
                service().list(access(false), organizationId, ProviderOwnerType.USER,
                        Optional.empty(), 0, 10);

        assertEquals(
                List.of(newest.id().toString(), oldest.id().toString()),
                page.stream().map(GitHubConnectionView::connectionId).toList());
        assertEquals(ProviderOwnerType.USER, page.get(0).ownerType());
        assertEquals(GitHubAuthenticationType.OAUTH_USER, page.get(0).authenticationType());
        assertEquals(
                List.of(oldest.id().toString()),
                service().list(access(false), organizationId, ProviderOwnerType.USER,
                                Optional.empty(), 1, 1)
                        .stream().map(GitHubConnectionView::connectionId).toList());
    }

    @Test
    void listRejectsOwnerTypeAndTeamIdMismatchesBeforeAnyRepositoryAccess() {
        GitHubConnectionApplicationService local = service();
        DomainValidationException userWithTeam = assertThrows(
                DomainValidationException.class,
                () -> local.list(access(false), organizationId, ProviderOwnerType.USER,
                        Optional.of(TeamId.generate()), 0, 10));
        assertTrue(userWithTeam.getMessage().contains("githubConnection.teamId"));
        DomainValidationException teamWithoutId = assertThrows(
                DomainValidationException.class,
                () -> local.list(access(false), organizationId, ProviderOwnerType.TEAM,
                        Optional.empty(), 0, 10));
        assertTrue(teamWithoutId.getMessage().contains("githubConnection.teamId"));
        DomainValidationException organizationOwner = assertThrows(
                DomainValidationException.class,
                () -> local.list(access(false), organizationId, ProviderOwnerType.ORGANIZATION,
                        Optional.empty(), 0, 10));
        assertTrue(organizationOwner.getMessage().contains("githubConnection.ownerType"));
        verify(connections, never()).findByOwner(any());
    }

    @Test
    void listingTeamConnectionsRequiresActiveMembership() {
        TeamInitialization team = TeamInitialization.create(actor, "Delivery Team", NOW);
        when(teams.findUninitializedById(organizationId, team.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, team.team().id()))
                .thenReturn(Optional.of(team.team()));
        when(memberships.findByTeam(organizationId, team.team().id()))
                .thenReturn(List.of());
        Connection owned = teamConnection(team.team(), NOW);
        when(connections.findByOwner(any())).thenReturn(List.of(owned));

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> service().list(access(false), organizationId, ProviderOwnerType.TEAM,
                        Optional.of(team.team().id()), 0, 10));

        assertTrue(denied.getMessage().contains("access this Team's GitHub Connections"));
        verify(connections, never()).findByOwner(any());
    }

    @Test
    void operationsAgainstUninitializedTeamsAreRejected() {
        TeamInitialization team = TeamInitialization.create(actor, "Initializing Team", NOW);
        when(teams.findUninitializedById(organizationId, team.team().id()))
                .thenReturn(Optional.of(mock(UninitializedTeam.class)));
        CredentialSecret secret = CredentialSecret.utf8("app-token");

        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> service().create(
                        commandContext(true),
                        organizationId,
                        new CreateGitHubConnectionRequest(
                                GitHubAuthenticationType.APP_INSTALLATION,
                                Optional.of(team.team().id()),
                                CredentialSubjectType.TEAM,
                                "4815",
                                Set.of("crewscope/repository-a"),
                                Optional.empty()),
                        secret));

        assertTrue(rejected.getMessage().contains("team.initializationStatus"));
        assertTrue(secret.isClosed());
        verify(credentials, never()).create(any(), any());
    }

    @Test
    void actorsOutsideTheOrganizationAndViewersOfForeignConnectionsAreDenied() {
        GitHubConnectionApplicationService local = service();
        PolicyDeniedException foreignOrganization = assertThrows(
                PolicyDeniedException.class,
                () -> local.list(access(false), OrganizationId.generate(),
                        ProviderOwnerType.USER, Optional.empty(), 0, 10));
        assertTrue(foreignOrganization.getMessage().contains("act in this Organization"));

        Principal otherUser = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Other Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        Connection foreign = Connection.authorize(
                ConnectionId.generate(),
                ProviderOwner.user(otherUser),
                KEY,
                "2718",
                CredentialId.generate(),
                Optional.empty(),
                otherUser,
                NOW);
        when(connections.findById(organizationId, foreign.id()))
                .thenReturn(Optional.of(foreign));

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> local.get(access(false), organizationId, foreign.id()));

        assertTrue(denied.getMessage().contains("view this GitHub Connection"));
        verify(connections).findById(organizationId, foreign.id());
    }

    @Test
    void oauthUserCreateRequestsMustNotCarryTeamShape() {
        CredentialSecret withTeam = CredentialSecret.utf8("token-one");
        DomainValidationException teamIdRejected = assertThrows(
                DomainValidationException.class,
                () -> service().create(
                        commandContext(false),
                        organizationId,
                        new CreateGitHubConnectionRequest(
                                GitHubAuthenticationType.OAUTH_USER,
                                Optional.of(TeamId.generate()),
                                CredentialSubjectType.TEAM,
                                "2718",
                                Set.of("crewscope/repository-a"),
                                Optional.empty()),
                        withTeam));
        assertTrue(teamIdRejected.getMessage().contains("githubConnection.owner"));
        assertTrue(withTeam.isClosed());

        CredentialSecret teamSubject = CredentialSecret.utf8("token-two");
        DomainValidationException subjectRejected = assertThrows(
                DomainValidationException.class,
                () -> service().create(
                        commandContext(false),
                        organizationId,
                        new CreateGitHubConnectionRequest(
                                GitHubAuthenticationType.OAUTH_USER,
                                Optional.empty(),
                                CredentialSubjectType.TEAM,
                                "2718",
                                Set.of("crewscope/repository-a"),
                                Optional.empty()),
                        teamSubject));
        assertTrue(subjectRejected.getMessage().contains("githubConnection.owner"));
        assertTrue(teamSubject.isClosed());
        verify(credentials, never()).create(any(), any());
    }

    @Test
    void appInstallationRequiresTeamIdAndTeamScopedSubjects() {
        CredentialSecret withoutTeam = CredentialSecret.utf8("token-one");
        DomainValidationException teamRequired = assertThrows(
                DomainValidationException.class,
                () -> service().create(
                        commandContext(true),
                        organizationId,
                        new CreateGitHubConnectionRequest(
                                GitHubAuthenticationType.APP_INSTALLATION,
                                Optional.empty(),
                                CredentialSubjectType.TEAM,
                                "4815",
                                Set.of("crewscope/repository-a"),
                                Optional.empty()),
                        withoutTeam));
        assertTrue(teamRequired.getMessage().contains("githubConnection.teamId"));
        assertTrue(withoutTeam.isClosed());

        TeamInitialization team = TeamInitialization.create(actor, "App Team", NOW);
        stubTeamReady(team);
        CredentialSecret principalSubject = CredentialSecret.utf8("token-two");
        DomainValidationException subjectRejected = assertThrows(
                DomainValidationException.class,
                () -> service().create(
                        commandContext(true),
                        organizationId,
                        new CreateGitHubConnectionRequest(
                                GitHubAuthenticationType.APP_INSTALLATION,
                                Optional.of(team.team().id()),
                                CredentialSubjectType.PRINCIPAL,
                                "4815",
                                Set.of("crewscope/repository-a"),
                                Optional.empty()),
                        principalSubject));
        assertTrue(subjectRejected.getMessage().contains("githubConnection.credentialSubjectType"));
        assertTrue(principalSubject.isClosed());
        verify(credentials, never()).create(any(), any());
    }

    @Test
    void organizationCredentialSubjectsAreRestrictedToPlatformAdministrators() {
        TeamInitialization team = TeamInitialization.create(actor, "Org Credential Team", NOW);
        stubTeamReady(team);

        CommandExecution<GitHubConnectionView> created = service().create(
                commandContext(true),
                organizationId,
                new CreateGitHubConnectionRequest(
                        GitHubAuthenticationType.APP_INSTALLATION,
                        Optional.of(team.team().id()),
                        CredentialSubjectType.ORGANIZATION,
                        "4815",
                        Set.of("crewscope/repository-a"),
                        Optional.empty()),
                CredentialSecret.utf8("org-token"));

        GitHubConnectionView result = created.result().orElseThrow();
        assertEquals(ProviderOwnerType.TEAM, result.ownerType());
        assertEquals(Optional.of(team.team().id()), result.teamId());
        assertEquals(GitHubAuthenticationType.APP_INSTALLATION, result.authenticationType());
        ArgumentCaptor<CredentialCreateRequest> credential =
                ArgumentCaptor.forClass(CredentialCreateRequest.class);
        verify(credentials).create(credential.capture(), any());
        assertEquals(CredentialSubjectType.ORGANIZATION, credential.getValue().subject().type());
        assertEquals(organizationId, credential.getValue().subject().organizationId());

        PolicyDeniedException nonAdministrator = assertThrows(
                PolicyDeniedException.class,
                () -> service().create(
                        commandContext(false),
                        organizationId,
                        new CreateGitHubConnectionRequest(
                                GitHubAuthenticationType.APP_INSTALLATION,
                                Optional.of(team.team().id()),
                                CredentialSubjectType.ORGANIZATION,
                                "4815",
                                Set.of("crewscope/repository-a"),
                                Optional.empty()),
                        CredentialSecret.utf8("org-token-two")));
        assertTrue(nonAdministrator.getMessage().contains("manage Team GitHub Connections"));
        verify(credentials, times(1)).create(any(), any());
    }

    // ------------------------------------------------------------------
    // Command journal block: event envelope, outbox and receipt linkage.
    // ------------------------------------------------------------------

    @Test
    void createJournalsAnExactConnectionEventThroughEventStoreOutboxAndReceipt() {
        TeamCommandContext command = commandContext(false);
        CommandExecution<GitHubConnectionView> execution = service().create(
                command,
                organizationId,
                new CreateGitHubConnectionRequest(
                        GitHubAuthenticationType.OAUTH_USER,
                        Optional.empty(),
                        CredentialSubjectType.PRINCIPAL,
                        "2718",
                        Set.of("crewscope/repository-a"),
                        Optional.empty()),
                CredentialSecret.utf8("oauth-token"));
        ArgumentCaptor<Connection> stored = ArgumentCaptor.forClass(Connection.class);
        verify(connections).create(stored.capture());
        Connection connection = stored.getValue();

        ArgumentCaptor<DomainEventEnvelope<?>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(envelope.capture());
        DomainEventEnvelope<?> event = envelope.getValue();
        assertEquals("GITHUB_CONNECTION_CREATED", event.eventType().value());
        assertEquals(SchemaVersion.V1, event.schemaVersion());
        assertEquals(organizationId, event.organizationId());
        assertEquals(Optional.empty(), event.teamId());
        assertEquals(Optional.empty(), event.workspaceId());
        assertEquals(AggregateReference.of("CONNECTION", connection.id()), event.aggregate());
        assertEquals(connection.version(), event.aggregateVersion());
        assertEquals(
                EventActor.principal(EventActorType.USER, actor.id()), event.actor());
        assertEquals(command.correlationId(), event.correlationId());
        assertEquals(command.causationId(), event.causationId());
        assertEquals(Optional.of(command.idempotencyKey().value()), event.idempotencyKey());
        assertEquals(NOW, event.occurredAt());
        assertEquals(ConnectionLifecycleChanged.from(connection), event.payload());

        ArgumentCaptor<PendingOutboxEvent> queued =
                ArgumentCaptor.forClass(PendingOutboxEvent.class);
        verify(outbox).enqueue(queued.capture());
        assertEquals(event.eventId(), queued.getValue().domainEventId());
        assertEquals(PendingOutboxEvent.DOMAIN_EVENTS_TOPIC, queued.getValue().topic());
        assertEquals(
                organizationId.value() + ":CONNECTION:" + connection.id().value(),
                queued.getValue().partitionKey());
        assertEquals(NOW, queued.getValue().createdAt());

        ArgumentCaptor<CommandReceipt> receipt =
                ArgumentCaptor.forClass(CommandReceipt.class);
        verify(receipts).complete(
                eq(organizationId), eq(command.idempotencyKey()), receipt.capture(), eq(NOW));
        assertEquals(event.eventId(), receipt.getValue().domainEventId());
        assertEquals(connection.version(), receipt.getValue().committedVersion());
        assertEquals(command.correlationId(), receipt.getValue().correlationId());
        assertEquals(execution.receipt(), receipt.getValue());
    }

    @Test
    void bindJournalsProviderBoundEventWithWorkspaceSubjectAndBindingAggregate() {
        TeamInitialization team = TeamInitialization.create(actor, "Binding Team", NOW);
        stubTeamReady(team);
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = userConnection("2718", NOW);
        ConnectionGrant grant = explicitGrant(
                connection, "github:repository:crewscope/repository-a");
        stubStored(connection, grant);
        stubVerified(connection);
        stubFoundation();
        TeamCommandContext command = commandContext(false);

        service().bind(
                command,
                organizationId,
                connection.id(),
                connection.version(),
                team.team().id(),
                false);

        ArgumentCaptor<ProviderBinding> stored = ArgumentCaptor.forClass(ProviderBinding.class);
        verify(bindings).create(stored.capture());
        ProviderBinding binding = stored.getValue();

        ArgumentCaptor<DomainEventEnvelope<?>> envelope =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(eventStore).append(envelope.capture());
        DomainEventEnvelope<?> event = envelope.getValue();
        assertEquals("GITHUB_PROVIDER_BOUND", event.eventType().value());
        assertEquals(SchemaVersion.V1, event.schemaVersion());
        assertEquals(organizationId, event.organizationId());
        assertEquals(Optional.of(team.team().id()), event.teamId());
        assertEquals(Optional.of(team.defaultWorkspace().id()), event.workspaceId());
        assertEquals(AggregateReference.of("PROVIDER_BINDING", binding.id()), event.aggregate());
        assertEquals(binding.version(), event.aggregateVersion());
        assertEquals(
                EventActor.principal(EventActorType.USER, actor.id()), event.actor());
        assertEquals(command.correlationId(), event.correlationId());
        assertEquals(command.causationId(), event.causationId());
        assertEquals(Optional.of(command.idempotencyKey().value()), event.idempotencyKey());
        assertEquals(NOW, event.occurredAt());
        assertEquals(ProviderBindingChanged.from(binding), event.payload());

        ArgumentCaptor<PendingOutboxEvent> queued =
                ArgumentCaptor.forClass(PendingOutboxEvent.class);
        verify(outbox).enqueue(queued.capture());
        assertEquals(event.eventId(), queued.getValue().domainEventId());
        assertEquals(
                organizationId.value() + ":PROVIDER_BINDING:" + binding.id().value(),
                queued.getValue().partitionKey());

        ArgumentCaptor<CommandReceipt> receipt =
                ArgumentCaptor.forClass(CommandReceipt.class);
        verify(receipts).complete(
                eq(organizationId), eq(command.idempotencyKey()), receipt.capture(), eq(NOW));
        assertEquals(binding.version(), receipt.getValue().committedVersion());
    }

    @Test
    void identicalCommandParametersProduceStableRequestHashesPerCommandType() {
        CreateGitHubConnectionRequest first = new CreateGitHubConnectionRequest(
                GitHubAuthenticationType.OAUTH_USER,
                Optional.empty(),
                CredentialSubjectType.PRINCIPAL,
                "2718",
                Set.of("crewscope/repository-a"),
                Optional.empty());
        CreateGitHubConnectionRequest second = new CreateGitHubConnectionRequest(
                GitHubAuthenticationType.OAUTH_USER,
                Optional.empty(),
                CredentialSubjectType.PRINCIPAL,
                "2718",
                Set.of("crewscope/repository-a"),
                Optional.empty());
        CreateGitHubConnectionRequest mutated = new CreateGitHubConnectionRequest(
                GitHubAuthenticationType.OAUTH_USER,
                Optional.empty(),
                CredentialSubjectType.PRINCIPAL,
                "2718",
                Set.of("crewscope/repository-b"),
                Optional.empty());
        GitHubConnectionApplicationService local = service();

        local.create(commandContext(false), organizationId, first,
                CredentialSecret.utf8("identical-token"));
        local.create(commandContext(false), organizationId, second,
                CredentialSecret.utf8("identical-token"));
        local.create(commandContext(false), organizationId, mutated,
                CredentialSecret.utf8("identical-token"));

        ArgumentCaptor<CommandReservationRequest> reservations =
                ArgumentCaptor.forClass(CommandReservationRequest.class);
        verify(receipts, times(3)).reserve(reservations.capture());
        List<CommandReservationRequest> captured = reservations.getAllValues();
        assertEquals("CREATE_GITHUB_CONNECTION", captured.get(0).commandType());
        assertEquals("CREATE_GITHUB_CONNECTION", captured.get(1).commandType());
        assertEquals(captured.get(0).requestHash(), captured.get(1).requestHash());
        assertNotEquals(captured.get(0).requestHash(), captured.get(2).requestHash());
        assertEquals(organizationId, captured.get(0).organizationId());
    }

    // ------------------------------------------------------------------
    // Provider foundation block: built-in definition/implementation bootstrap.
    // ------------------------------------------------------------------

    @Test
    void firstBindBootstrapsTheBuiltInProviderDefinitionAndImplementation() {
        TeamInitialization team = TeamInitialization.create(actor, "Bootstrap Team", NOW);
        stubTeamReady(team);
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = userConnection("2718", NOW);
        stubStored(connection, explicitGrant(connection, "github:repository:crewscope/repository-a"));
        stubVerified(connection);
        stubFoundation();

        service().bind(
                commandContext(false),
                organizationId,
                connection.id(),
                connection.version(),
                team.team().id(),
                false);

        verify(bootstrapLock).acquire(organizationId);
        ArgumentCaptor<ProviderDefinition> definition =
                ArgumentCaptor.forClass(ProviderDefinition.class);
        verify(definitions).create(definition.capture());
        assertEquals(
                registration.definitionId(organizationId), definition.getValue().id());
        assertEquals(KEY, definition.getValue().key());
        assertEquals(ProviderType.SOURCE_CODE, definition.getValue().type());
        assertEquals("1.0.0", definition.getValue().interfaceVersion());
        assertEquals("GitHub", definition.getValue().displayName());
        assertEquals(
                GitHubConnectionApplicationService.DELIVERY_CAPABILITIES,
                definition.getValue().capabilities());
        assertTrue(definition.getValue().isActive());
        ArgumentCaptor<ProviderImplementation> implementation =
                ArgumentCaptor.forClass(ProviderImplementation.class);
        verify(implementations).create(implementation.capture());
        assertEquals(
                registration.implementationId(organizationId),
                implementation.getValue().id());
        assertEquals(definition.getValue().id(), implementation.getValue().definitionId());
        assertEquals(KEY, implementation.getValue().key());
        assertEquals("1.0.0", implementation.getValue().implementationVersion());
        assertEquals(
                ProviderConnectionRequirement.REQUIRED,
                implementation.getValue().connectionRequirement());
        assertEquals(Optional.of(KEY), implementation.getValue().connectorKey());
    }

    @Test
    void aPersistedDefinitionConflictingWithTheBuiltInContractIsRejected() {
        TeamInitialization team = TeamInitialization.create(actor, "Conflict Team", NOW);
        stubTeamReady(team);
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = userConnection("2718", NOW);
        stubStored(connection, explicitGrant(connection, "github:repository:crewscope/repository-a"));
        stubVerified(connection);
        ProviderDefinition conflicting = mock(ProviderDefinition.class);
        when(conflicting.id()).thenReturn(new ProviderDefinitionId(UUID.randomUUID()));
        when(definitions.findByKey(organizationId, KEY))
                .thenReturn(Optional.of(conflicting));
        when(implementations.findByDefinition(any(), any())).thenReturn(List.of());

        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        connection.id(),
                        connection.version(),
                        team.team().id(),
                        false));

        assertTrue(rejected.getMessage().contains("githubProvider.definition"));
        verify(definitions, never()).create(any());
        verify(implementations, never()).create(any());
        verify(bindings, never()).create(any());
    }

    @Test
    void ambiguousOrConflictingImplementationsAreRejected() {
        TeamInitialization team = TeamInitialization.create(actor, "Ambiguity Team", NOW);
        stubTeamReady(team);
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = userConnection("2718", NOW);
        stubStored(connection, explicitGrant(connection, "github:repository:crewscope/repository-a"));
        stubVerified(connection);
        when(definitions.findByKey(organizationId, KEY)).thenReturn(Optional.empty());
        when(definitions.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProviderImplementation first = mock(ProviderImplementation.class);
        when(first.key()).thenReturn(KEY);
        ProviderImplementation second = mock(ProviderImplementation.class);
        when(second.key()).thenReturn(KEY);
        when(implementations.findByDefinition(any(), any()))
                .thenReturn(List.of(first, second));

        DomainValidationException ambiguous = assertThrows(
                DomainValidationException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        connection.id(),
                        connection.version(),
                        team.team().id(),
                        false));
        assertTrue(ambiguous.getMessage().contains("contains ambiguous registrations"));
        verify(bindings, never()).create(any());

        ProviderImplementation conflicting = mock(ProviderImplementation.class);
        when(conflicting.key()).thenReturn(KEY);
        when(conflicting.supports(any(), any())).thenReturn(false);
        when(implementations.findByDefinition(any(), any()))
                .thenReturn(List.of(conflicting));
        DomainValidationException conflict = assertThrows(
                DomainValidationException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        connection.id(),
                        connection.version(),
                        team.team().id(),
                        false));
        assertTrue(conflict.getMessage().contains(
                "githubProvider.implementation"));
        verify(bindings, never()).create(any());
    }

    // ------------------------------------------------------------------
    // Access fact block: managed-access ordering and request assembly.
    // ------------------------------------------------------------------

    @Test
    void verifyAssemblesTheExactAccessRequestFromCurrentConnectionAndGrantFacts() {
        Connection connection = userConnection("2718", NOW);
        ConnectionGrant grant = explicitGrant(
                connection, "github:repository:crewscope/repository-a");
        stubStored(connection, grant);
        UUID correlationId = UUID.randomUUID();

        service().verify(
                access(false), organizationId, connection.id(), connection.version(),
                correlationId);

        ArgumentCaptor<VerifyGitHubConnectionRequest> request =
                ArgumentCaptor.forClass(VerifyGitHubConnectionRequest.class);
        verify(provider).verifyConnection(request.capture());
        GitHubAccessRequest facts = request.getValue().access();
        assertEquals(organizationId, facts.organizationId());
        assertEquals(connection.id(), facts.connectionId());
        assertEquals(connection.version(), facts.expectedConnectionVersion());
        assertEquals(grant.id(), facts.connectionGrantId());
        assertEquals(grant.version(), facts.expectedGrantVersion());
        assertEquals(connection.owner(), facts.grantee());
        assertEquals(grant.grantedAccess(), facts.requestedAccess());
        assertEquals(actor.id(), facts.actor());
        assertEquals(correlationId, facts.correlationId());
        assertEquals(GitHubAuthenticationType.OAUTH_USER, request.getValue().authenticationType());
        assertEquals(
                new GitHubRepositoryPolicy(
                        Set.of("crewscope/repository-a"),
                        Set.of("crewscope"),
                        true,
                        false,
                        true),
                request.getValue().repositoryPolicy());
    }

    @Test
    void managedAccessFailsClosedOnMissingGrantsAndStaleVersions() {
        Connection connection = userConnection("2718", NOW);
        stubStored(connection, null);

        GitHubProviderException missingGrant = assertThrows(
                GitHubProviderException.class,
                () -> service().verify(
                        access(false), organizationId, connection.id(),
                        connection.version(), UUID.randomUUID()));
        assertEquals(GitHubProviderErrorCode.GRANT_UNAVAILABLE, missingGrant.code());
        assertTrue(missingGrant.getMessage().contains("Grant is unavailable"));

        OptimisticLockConflictException staleVersion = assertThrows(
                OptimisticLockConflictException.class,
                () -> service().verify(
                        access(false), organizationId, connection.id(),
                        connection.version() + 5, UUID.randomUUID()));

        assertTrue(staleVersion.getMessage().contains("version conflict"));
        verify(provider, never()).verifyConnection(any());
    }

    @Test
    void managedAccessChecksManageOwnershipBeforeVersionAndGrantFacts() {
        TeamInitialization team = TeamInitialization.create(actor, "Gate Team", NOW);
        stubTeamReady(team);
        Connection connection = teamConnection(team.team(), NOW);
        stubStored(connection, null);

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> service().verify(
                        access(false), organizationId, connection.id(),
                        connection.version() + 5, UUID.randomUUID()));

        assertTrue(denied.getMessage().contains("manage Team GitHub Connections"));
        verify(provider, never()).verifyConnection(any());
    }

    @Test
    void preflightRejectsBindingsFromOtherConnectionsOrWrongRevisions() {
        TeamInitialization team = TeamInitialization.create(actor, "Preflight Team", NOW);
        stubTeamReady(team);
        Connection connection = userConnection("2718", NOW);
        ConnectionGrant grant = explicitGrant(
                connection, "github:repository:crewscope/repository-a");
        stubStored(connection, grant);
        ProviderBindingId bindingId = ProviderBindingId.generate();

        ProviderBinding foreign = mock(ProviderBinding.class);
        when(foreign.connectionId()).thenReturn(Optional.of(ConnectionId.generate()));
        when(bindings.findById(organizationId, bindingId)).thenReturn(Optional.of(foreign));
        assertThrows(
                io.crewscope.domain.shared.error.AggregateNotFoundException.class,
                () -> service().preflightRepository(
                        access(false), organizationId, connection.id(),
                        connection.version(), bindingId, "12345", UUID.randomUUID()));

        ProviderBinding wrongRevision = mock(ProviderBinding.class);
        when(wrongRevision.connectionId()).thenReturn(Optional.of(connection.id()));
        when(wrongRevision.connectionVersion()).thenReturn(Optional.of(connection.version() + 9));
        when(bindings.findById(organizationId, bindingId)).thenReturn(Optional.of(wrongRevision));
        assertThrows(
                io.crewscope.domain.shared.error.AggregateNotFoundException.class,
                () -> service().preflightRepository(
                        access(false), organizationId, connection.id(),
                        connection.version(), bindingId, "12345", UUID.randomUUID()));

        verify(provider, never()).preflightRepository(any());
    }

    @Test
    void preflightRejectsStaleBindingAuthorization() {
        TeamInitialization team = TeamInitialization.create(actor, "Stale Binding Team", NOW);
        stubTeamReady(team);
        Connection connection = userConnection("2718", NOW);
        ConnectionGrant grant = explicitGrant(
                connection, "github:repository:crewscope/repository-a");
        stubStored(connection, grant);
        ProviderBindingId bindingId = ProviderBindingId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        ProviderDefinitionId definitionId = new ProviderDefinitionId(UUID.randomUUID());
        ProviderImplementationId implementationId = new ProviderImplementationId(UUID.randomUUID());
        ProviderBinding binding = mock(ProviderBinding.class);
        when(binding.connectionId()).thenReturn(Optional.of(connection.id()));
        when(binding.connectionVersion()).thenReturn(Optional.of(connection.version()));
        when(binding.connectionGrantId()).thenReturn(Optional.of(grant.id()));
        when(binding.connectionGrantVersion()).thenReturn(Optional.of(grant.version()));
        when(binding.owner()).thenReturn(connection.owner());
        when(binding.providerType()).thenReturn(ProviderType.SOURCE_CODE);
        when(binding.target()).thenReturn(new ProviderBindingTarget(
                organizationId, team.team().id(), workspaceId,
                ProviderBindingTargetType.WORKSPACE, Optional.empty()));
        when(binding.executionIdentity())
                .thenReturn(Optional.of(ProviderExecutionIdentity.DELEGATED_USER));
        when(binding.definitionId()).thenReturn(definitionId);
        when(binding.implementationId()).thenReturn(implementationId);
        when(definitions.findById(organizationId, definitionId))
                .thenReturn(Optional.of(mock(ProviderDefinition.class)));
        when(implementations.findById(organizationId, implementationId))
                .thenReturn(Optional.of(mock(ProviderImplementation.class)));
        when(binding.currentAccess(any(), any(), any(), any(), eq(NOW)))
                .thenReturn(Optional.empty());
        when(bindings.findById(organizationId, bindingId)).thenReturn(Optional.of(binding));

        GitHubProviderException stale = assertThrows(
                GitHubProviderException.class,
                () -> service().preflightRepository(
                        access(false), organizationId, connection.id(),
                        connection.version(), bindingId, "12345", UUID.randomUUID()));

        assertEquals(GitHubProviderErrorCode.GRANT_UNAVAILABLE, stale.code());
        assertTrue(stale.getMessage().contains("Binding is stale"));
        verify(provider, never()).preflightRepository(any());
    }

    @Test
    void bindingAConnectionThatWasNeverVerifiedIsRejected() {
        TeamInitialization team = TeamInitialization.create(actor, "Unverified Team", NOW);
        stubTeamReady(team);
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = userConnection("2718", NOW);
        stubStored(connection, explicitGrant(connection, "github:repository:crewscope/repository-a"));

        GitHubProviderException unverified = assertThrows(
                GitHubProviderException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        connection.id(),
                        connection.version(),
                        team.team().id(),
                        false));

        assertEquals(GitHubProviderErrorCode.CONNECTION_UNAVAILABLE, unverified.code());
        assertTrue(unverified.getMessage().contains("has not been verified"));
        verify(bindings, never()).create(any());
    }

    @Test
    void repositorySelectionMustStayInsideTheGrantAndPinConcreteResources() {
        TeamInitialization team = TeamInitialization.create(actor, "Selection Team", NOW);
        stubTeamReady(team);
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        Connection connection = userConnection("2718", NOW);
        stubVerified(connection);
        stubFoundation();

        Connection discovery = userConnection("2718", NOW);
        stubStored(discovery, discoveryGrant(discovery));
        stubVerified(discovery);
        DomainValidationException unrestricted = assertThrows(
                DomainValidationException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        discovery.id(),
                        discovery.version(),
                        team.team().id(),
                        false));
        assertTrue(unrestricted.getMessage().contains(
                "must select at least one GitHub repository"));

        Connection pinned = userConnection("2719", NOW);
        ConnectionGrant pinGrant =
                explicitGrant(pinned, "github:repository:crewscope/repository-a");
        stubStored(pinned, pinGrant);
        stubVerified(pinned);
        GitHubProviderException unknownRepository = assertThrows(
                GitHubProviderException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        pinned.id(),
                        pinned.version(),
                        team.team().id(),
                        false,
                        Optional.of(Set.of("999"))));
        assertEquals(GitHubProviderErrorCode.RESOURCE_UNAVAILABLE, unknownRepository.code());
        assertTrue(unknownRepository.getMessage().contains("repository is unavailable"));

        GitHubRepositoryCatalogEntry outsideGrant =
                catalogEntry(pinned, "other", "repo", pinned.version(), NOW, LATER,
                        GitHubRepositoryStatus.DELIVERABLE);
        when(githubRepository.findRepository(organizationId, pinned.id(), "42"))
                .thenReturn(Optional.of(outsideGrant));
        GitHubProviderException unauthorized = assertThrows(
                GitHubProviderException.class,
                () -> service().bind(
                        commandContext(false),
                        organizationId,
                        pinned.id(),
                        pinned.version(),
                        team.team().id(),
                        false,
                        Optional.of(Set.of("42"))));
        assertEquals(GitHubProviderErrorCode.GRANT_UNAVAILABLE, unauthorized.code());
        assertTrue(unauthorized.getMessage().contains(
                "does not authorize the selected repository"));
        verify(bindings, never()).create(any());
    }

    // ------------------------------------------------------------------
    // Allowlist decoding: repository-prefix match with original case kept.
    // ------------------------------------------------------------------

    @Test
    void repositoryAllowlistDecodingKeepsOriginalCaseAndRejectsForeignResources() {
        Connection mixedCase = userConnection("2718", NOW);
        stubStored(mixedCase, explicitGrant(
                mixedCase, "github:repository:CrewScope/Repository-A"));

        GitHubConnectionView view = service().get(
                access(false), organizationId, mixedCase.id());

        assertEquals(List.of("CrewScope/Repository-A"), view.repositoryAllowlist());

        Connection foreign = userConnection("2719", NOW);
        stubStored(foreign, explicitGrant(
                foreign, "github:organization:crewscope"));

        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> service().get(access(false), organizationId, foreign.id()));
        assertTrue(rejected.getMessage().contains("githubConnection.repositoryAllowlist"));
    }

    // ------------------------------------------------------------------
    // Remaining read models: health, catalog, bindings, validation gates.
    // ------------------------------------------------------------------

    @Test
    void healthClassifiesEveryFailureDimensionAndWebhookReadiness() {
        Connection healthy = userConnection("1001", NOW);
        stubStored(healthy, explicitGrant(healthy, "github:repository:crewscope/repository-a"));
        stubVerified(healthy);

        Connection suspendedSource = userConnection("1002", NOW);
        Connection suspended = suspendedSource.suspend(
                suspendedSource.version(), actor, NOW);
        when(connections.findById(organizationId, suspended.id()))
                .thenReturn(Optional.of(suspended));
        when(grants.findByConnectionAndGrantee(suspended.id(), suspended.owner()))
                .thenReturn(List.of(explicitGrant(
                        suspendedSource, "github:repository:crewscope/repository-a")));

        Connection grantless = userConnection("1003", NOW);
        stubStored(grantless, null);

        Connection credentialless = userConnection("1004", NOW);
        stubStored(credentialless,
                explicitGrant(credentialless, "github:repository:crewscope/repository-a"));
        stubVerified(credentialless);
        when(credentials.describe(any(), any())).thenAnswer(invocation -> {
            io.crewscope.application.credential.CredentialReference reference =
                    invocation.getArgument(0);
            return reference.credentialId().equals(credentialless.credentialId())
                    ? Optional.empty()
                    : Optional.of(descriptor);
        });

        Connection unverified = userConnection("1005", NOW);
        stubStored(unverified, explicitGrant(unverified, "github:repository:crewscope/repository-a"));

        Connection throttled = userConnection("1006", NOW);
        stubStored(throttled, explicitGrant(throttled, "github:repository:crewscope/repository-a"));
        stubVerified(throttled);
        when(githubRepository.findCurrentRateLimit(organizationId, throttled.id(), "core"))
                .thenReturn(Optional.of(new GitHubRateLimitSnapshot(
                        UUID.randomUUID(), organizationId, throttled.id(),
                        throttled.version(), "core", 5000, 0, 5000, LATER, NOW,
                        actor.id())));

        GitHubConnectionApplicationService local = service();
        assertEquals("HEALTHY", local.health(access(false), organizationId, healthy.id())
                .authorizationStatus());
        assertEquals("CONNECTION_UNAVAILABLE",
                local.health(access(false), organizationId, suspended.id())
                        .authorizationStatus());
        assertEquals("GRANT_UNAVAILABLE",
                local.health(access(false), organizationId, grantless.id())
                        .authorizationStatus());
        assertEquals("CREDENTIAL_UNAVAILABLE",
                local.health(access(false), organizationId, credentialless.id())
                        .authorizationStatus());
        assertEquals("VERIFICATION_REQUIRED",
                local.health(access(false), organizationId, unverified.id())
                        .authorizationStatus());
        GitHubAuthorizationHealthView limited =
                local.health(access(false), organizationId, throttled.id());
        assertEquals("RATE_LIMITED", limited.authorizationStatus());
        assertTrue(limited.rateLimit().isPresent());

        GitHubAuthorizationHealthView ready = local.health(access(false), organizationId,
                healthy.id());
        assertTrue(ready.connectionUsable());
        assertTrue(ready.grantUsable());
        assertTrue(ready.credentialUsable());
        assertTrue(ready.profileCurrent());
        assertEquals("CONFIGURED", ready.webhookStatus());

        assertEquals("NOT_CONFIGURED", service(false).health(
                        access(false), organizationId, healthy.id())
                .webhookStatus());
    }

    @Test
    void listCatalogReturnsOnlyCurrentDeliverableRevisionsSortedByFullName() {
        Connection connection = userConnection("2718", NOW);
        stubStored(connection, explicitGrant(
                connection, "github:repository:crewscope/repository-a"));
        stubVerified(connection);
        GitHubRepositoryCatalogEntry current = catalogEntry(
                connection, "crewscope", "zeta", connection.version(), NOW, LATER,
                GitHubRepositoryStatus.DELIVERABLE);
        GitHubRepositoryCatalogEntry alsoCurrent = catalogEntry(
                connection, "crewscope", "alpha", connection.version(), NOW, LATER,
                GitHubRepositoryStatus.DELIVERABLE);
        GitHubRepositoryCatalogEntry staleRevision = catalogEntry(
                connection, "crewscope", "stale-revision", connection.version() + 9,
                NOW, LATER, GitHubRepositoryStatus.DELIVERABLE);
        GitHubRepositoryCatalogEntry staleStatus = catalogEntry(
                connection, "crewscope", "stale-status", connection.version(),
                NOW, LATER, GitHubRepositoryStatus.STALE);
        GitHubRepositoryCatalogEntry expired = catalogEntry(
                connection, "crewscope", "expired", connection.version(),
                EARLIER, NOW, GitHubRepositoryStatus.DELIVERABLE);
        when(githubRepository.findDeliverableRepositories(organizationId, connection.id()))
                .thenReturn(List.of(current, staleRevision, expired, alsoCurrent, staleStatus));

        List<GitHubRepositoryView> catalog = service().listCatalog(
                access(false), organizationId, connection.id());

        assertEquals(
                List.of("crewscope/alpha", "crewscope/zeta"),
                catalog.stream().map(GitHubRepositoryView::fullName).toList());
    }

    @Test
    void listBindingsFiltersByConnectionSortsByIdAndRejectsCrossTeamAccess() {
        TeamInitialization team = TeamInitialization.create(actor, "Bindings Team", NOW);
        stubTeamReady(team);
        Connection connection = userConnection("2718", NOW);
        ConnectionGrant grant = explicitGrant(
                connection, "github:repository:crewscope/repository-a");
        stubStored(connection, grant);
        WorkspaceId workspaceId = WorkspaceId.generate();
        ProviderBindingTarget target = new ProviderBindingTarget(
                organizationId, team.team().id(), workspaceId,
                ProviderBindingTargetType.WORKSPACE, Optional.empty());
        ProviderBinding first = mock(ProviderBinding.class);
        when(first.id()).thenReturn(ProviderBindingId.from(
                "00000000-0000-0000-0000-000000000001"));
        when(first.connectionId()).thenReturn(Optional.of(connection.id()));
        when(first.connectionVersion()).thenReturn(Optional.of(connection.version()));
        when(first.connectionGrantId()).thenReturn(Optional.of(grant.id()));
        when(first.connectionGrantVersion()).thenReturn(Optional.of(grant.version()));
        when(first.executionIdentity())
                .thenReturn(Optional.of(ProviderExecutionIdentity.DELEGATED_USER));
        when(first.effectiveAccess()).thenReturn(grant.grantedAccess());
        when(first.status()).thenReturn(ProviderRegistrationStatus.ACTIVE);
        when(first.target()).thenReturn(target);
        ProviderBinding second = mock(ProviderBinding.class);
        when(second.id()).thenReturn(ProviderBindingId.from(
                "00000000-0000-0000-0000-000000000002"));
        when(second.connectionId()).thenReturn(Optional.of(connection.id()));
        when(second.connectionVersion()).thenReturn(Optional.of(connection.version()));
        when(second.connectionGrantId()).thenReturn(Optional.of(grant.id()));
        when(second.connectionGrantVersion()).thenReturn(Optional.of(grant.version()));
        when(second.executionIdentity())
                .thenReturn(Optional.of(ProviderExecutionIdentity.DELEGATED_USER));
        when(second.effectiveAccess()).thenReturn(grant.grantedAccess());
        when(second.status()).thenReturn(ProviderRegistrationStatus.ACTIVE);
        when(second.target()).thenReturn(target);
        ProviderBinding foreign = mock(ProviderBinding.class);
        when(foreign.id()).thenReturn(ProviderBindingId.from(
                "00000000-0000-0000-0000-000000000003"));
        when(foreign.connectionId()).thenReturn(Optional.of(ConnectionId.generate()));
        when(bindings.findCandidates(any())).thenReturn(List.of(second, foreign, first));

        List<GitHubProviderBindingView> listed = service().listBindings(
                access(false), organizationId, connection.id(), team.team().id());

        assertEquals(2, listed.size());
        assertEquals(first.id().toString(), listed.get(0).bindingId());
        assertEquals(second.id().toString(), listed.get(1).bindingId());
        assertEquals(
                List.of("crewscope/repository-a"), listed.get(0).repositoryAllowlist());

        TeamInitialization otherTeam = TeamInitialization.create(actor, "Other Team", NOW);
        when(teams.findUninitializedById(organizationId, otherTeam.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, otherTeam.team().id()))
                .thenReturn(Optional.of(otherTeam.team()));
        Connection teamOwned = teamConnection(team.team(), NOW);
        stubStored(teamOwned, grant);

        PolicyDeniedException crossTeam = assertThrows(
                PolicyDeniedException.class,
                () -> service().listBindings(
                        access(false), organizationId, teamOwned.id(),
                        otherTeam.team().id()));
        assertTrue(crossTeam.getMessage().contains("across Teams"));
    }

    @Test
    void revokeReasonsAndPageBoundsAreValidatedBeforeAnyRepositoryAccess() {
        GitHubConnectionApplicationService local = service();
        Connection connection = userConnection("2718", NOW);

        DomainValidationException blankReason = assertThrows(
                DomainValidationException.class,
                () -> local.revoke(
                        commandContext(false), organizationId, connection.id(),
                        connection.version(), "  "));
        assertTrue(blankReason.getMessage().contains("githubConnection.revokeReason"));
        DomainValidationException longReason = assertThrows(
                DomainValidationException.class,
                () -> local.revoke(
                        commandContext(false), organizationId, connection.id(),
                        connection.version(), "x".repeat(501)));
        assertTrue(longReason.getMessage().contains("githubConnection.revokeReason"));
        verify(connections, never()).findById(any(), any());

        DomainValidationException negativeOffset = assertThrows(
                DomainValidationException.class,
                () -> local.list(access(false), organizationId, ProviderOwnerType.USER,
                        Optional.empty(), -1, 10));
        assertTrue(negativeOffset.getMessage().contains("pagination"));
        DomainValidationException zeroLimit = assertThrows(
                DomainValidationException.class,
                () -> local.list(access(false), organizationId, ProviderOwnerType.USER,
                        Optional.empty(), 0, 0));
        assertTrue(zeroLimit.getMessage().contains("pagination"));
        DomainValidationException hugeLimit = assertThrows(
                DomainValidationException.class,
                () -> local.list(access(false), organizationId, ProviderOwnerType.USER,
                        Optional.empty(), 0, 101));
        assertTrue(hugeLimit.getMessage().contains("pagination"));
        verify(connections, never()).findByOwner(any());
    }
}
