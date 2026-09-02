package io.crewscope.application.github;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStatus;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.credential.CredentialSubjectType;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingQuery;
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
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.provider.event.ConnectionLifecycleChanged;
import io.crewscope.domain.provider.event.ProviderBindingChanged;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workspace.Workspace;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Collectors;

/** Secure application boundary for GitHub Connection, Catalog and remote Preflight APIs. */
public final class GitHubConnectionApplicationService {

    public static final String CONNECTOR_KEY = "github-source-code";
    public static final String CREDENTIAL_TYPE = "GITHUB_TOKEN";
    public static final ProviderCapabilities DELIVERY_CAPABILITIES = ProviderCapabilities.of(
            "source.repository.catalog",
            "source.repository.read",
            "source.repository.push",
            "source.pull-request.create");
    private static final String REPOSITORY_RESOURCE_PREFIX = "github:repository:";
    private static final String CREATE_CONNECTION = "CREATE_GITHUB_CONNECTION";
    private static final String BIND_CONNECTION = "BIND_GITHUB_CONNECTION";
    private static final String REVOKE_CONNECTION = "REVOKE_GITHUB_CONNECTION";
    private static final String CONNECTION_AGGREGATE = "CONNECTION";
    private static final String BINDING_AGGREGATE = "PROVIDER_BINDING";

    private final ConnectionRepository connections;
    private final ConnectionGrantRepository grants;
    private final CredentialStore credentials;
    private final GitHubProviderRepository githubRepository;
    private final GitHubProviderPort provider;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository memberRoles;
    private final WorkspaceRepository workspaces;
    private final ProviderDefinitionRepository definitions;
    private final ProviderImplementationRepository implementations;
    private final ProviderBindingRepository bindings;
    private final ProviderBootstrapLock providerBootstrapLock;
    private final BuiltInProviderRegistration githubRegistration;
    private final DomainEventStore eventStore;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final GitHubConnectionPolicySettings policySettings;

    public GitHubConnectionApplicationService(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            GitHubProviderRepository githubRepository,
            GitHubProviderPort provider,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository memberRoles,
            WorkspaceRepository workspaces,
            ProviderDefinitionRepository definitions,
            ProviderImplementationRepository implementations,
            ProviderBindingRepository bindings,
            ProviderBootstrapLock providerBootstrapLock,
            BuiltInProviderRegistration githubRegistration,
            DomainEventStore eventStore,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            GitHubConnectionPolicySettings policySettings) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.githubRepository = Objects.requireNonNull(githubRepository, "githubRepository");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.memberRoles = Objects.requireNonNull(memberRoles, "memberRoles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.implementations = Objects.requireNonNull(implementations, "implementations");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.providerBootstrapLock = Objects.requireNonNull(
                providerBootstrapLock, "providerBootstrapLock");
        this.githubRegistration = Objects.requireNonNull(githubRegistration, "githubRegistration");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        if (githubRegistration.type() != ProviderType.SOURCE_CODE
                || !CONNECTOR_KEY.equals(githubRegistration.implementationKey())
                || !githubRegistration.capabilities().equals(DELIVERY_CAPABILITIES)) {
            throw new IllegalArgumentException("githubRegistration is incompatible");
        }
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.policySettings = Objects.requireNonNull(policySettings, "policySettings");
    }

    /** Atomically stores the encrypted secret, Connection and exact Repository Grant. */
    public CommandExecution<GitHubConnectionView> create(
            TeamCommandContext context,
            OrganizationId organizationId,
            CreateGitHubConnectionRequest request,
            CredentialSecret plaintext) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CreateGitHubConnectionRequest required = Objects.requireNonNull(request, "request");
        try (CredentialSecret secret = Objects.requireNonNull(plaintext, "plaintext")) {
            GitHubRepositoryPolicy policy = policySettings.policyFor(required.repositoryAllowlist());
            ProviderAccessScope grantedAccess = access(policy.repositoryAllowlist());
            CommandRequestHash hash = CommandRequestHash.sha256(
                    CREATE_CONNECTION,
                    trusted.access().actor().id().toString(),
                    organizationId.toString(),
                    required.authenticationType().name(),
                    required.teamId().map(Object::toString).orElse(""),
                    required.credentialSubjectType().name(),
                    required.externalAccountId(),
                    String.join("\n", policy.repositoryAllowlist().stream().sorted().toList()),
                    required.expiresAt().map(Object::toString).orElse(""),
                    secretFingerprint(secret));
            UUID commandId = UUID.randomUUID();
            return transactions.required(() -> {
                UtcTimestamp now = timeProvider.now();
                Principal actor = requireOrganizationUser(trusted.access(), organizationId);
                OwnerFacts ownerFacts = resolveCreateOwner(trusted.access(), required, now);
                CommandReservation reservation = reserve(
                        trusted, organizationId, CREATE_CONNECTION, hash, commandId, now);
                if (!reservation.acquired()) {
                    return CommandExecution.replayed(reservation.receipt().orElseThrow());
                }
                ConnectionId connectionId = ConnectionId.generate();
                CredentialId credentialId = CredentialId.generate();
                credentials.create(
                        new CredentialCreateRequest(
                                credentialId,
                                ownerFacts.credentialSubject(),
                                "github-connection:" + connectionId,
                                CONNECTOR_KEY,
                                Optional.of(connectionId.value()),
                                CREDENTIAL_TYPE,
                                Map.of(
                                        "authenticationType", required.authenticationType().name(),
                                        "ownerType", ownerFacts.owner().type().name()),
                                required.expiresAt(),
                                actor.id()),
                        secret);
                Connection connection = connections.create(Connection.authorize(
                        connectionId,
                        ownerFacts.owner(),
                        CONNECTOR_KEY,
                        required.externalAccountId(),
                        credentialId,
                        required.expiresAt(),
                        actor,
                        now));
                ConnectionGrant grant = grants.create(ConnectionGrant.grant(
                        ConnectionGrantId.generate(),
                        connection,
                        ownerFacts.owner(),
                        grantedAccess,
                        now,
                        required.expiresAt(),
                        actor,
                        now));
                CommandReceipt receipt = appendConnectionEvent(
                        trusted, commandId, connection, "GITHUB_CONNECTION_CREATED", now);
                return CommandExecution.completed(
                        view(connection, grant, actor.id()), receipt);
            });
        }
    }

    public List<GitHubConnectionView> list(
            TeamAccessContext context,
            OrganizationId organizationId,
            ProviderOwnerType ownerType,
            Optional<TeamId> teamId,
            int offset,
            int limit) {
        requirePage(offset, limit);
        ProviderOwner owner = resolveListOwner(context, organizationId, ownerType, teamId);
        return connections.findByOwner(owner).stream()
                .filter(value -> CONNECTOR_KEY.equals(value.connectorKey()))
                .sorted(Comparator.comparing(value -> value.audit().createdAt(), Comparator.reverseOrder()))
                .skip(offset)
                .limit(limit)
                .map(value -> view(value, currentGrant(value).orElse(null), context.actor().id()))
                .toList();
    }

    public GitHubConnectionView get(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        Connection connection = requireVisibleConnection(context, organizationId, connectionId);
        return view(connection, currentGrant(connection).orElse(null), context.actor().id());
    }

    /** Binds a verified Connection to one active Team Workspace with immutable authorization pins. */
    public CommandExecution<GitHubProviderBindingView> bind(
            TeamCommandContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedConnectionVersion,
            TeamId teamId,
            boolean defaultUsage) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CommandRequestHash hash = CommandRequestHash.sha256(
                BIND_CONNECTION,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                connectionId.toString(),
                Long.toString(expectedConnectionVersion),
                teamId.toString(),
                Boolean.toString(defaultUsage));
        UUID commandId = UUID.randomUUID();
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Connection connection = requireVisibleConnection(
                    trusted.access(), organizationId, connectionId);
            requireBindingAuthority(trusted.access(), connection.owner(), teamId, now);
            if (connection.version() != expectedConnectionVersion) {
                connection.suspend(expectedConnectionVersion, trusted.access().actor(), now);
            }
            requireCurrentProfile(connection);
            CommandReservation reservation = reserve(
                    trusted, organizationId, BIND_CONNECTION, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            ConnectionGrant grant = requireGrant(connection);
            Team team = requireTeam(organizationId, teamId);
            Workspace workspace = workspaces.findById(organizationId, team.defaultWorkspaceId())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "Workspace", team.defaultWorkspaceId()));
            ProviderFoundationFacts foundation = providerFoundation(
                    organizationId, trusted.access().actor(), now);
            ProviderBindingTarget target = ProviderBindingTarget.workspace(workspace);
            List<ProviderBinding> existing = bindings.findCandidates(new ProviderBindingQuery(
                            organizationId,
                            team.id(),
                            workspace.id(),
                            Optional.empty(),
                            connection.owner(),
                            ProviderType.SOURCE_CODE,
                            Optional.of(executionIdentity(connection.owner().type())))).stream()
                    .filter(value -> value.connectionId().filter(connection.id()::equals).isPresent())
                    .toList();
            if (existing.size() > 1) {
                throw new DomainValidationException(
                        "githubProviderBinding", "contains ambiguous active candidates");
            }
            if (existing.size() == 1) {
                throw new DomainValidationException(
                        "githubProviderBinding", "already exists for this Connection and Workspace");
            }
            ProviderBinding binding = ProviderBinding.bind(
                    ProviderBindingId.generate(),
                    target,
                    connection.owner(),
                    foundation.definition(),
                    foundation.implementation(),
                    Optional.of(connection),
                    Optional.of(grant),
                    grant.grantedAccess(),
                    defaultUsage,
                    trusted.access().actor(),
                    now);
            ProviderBinding committed = bindings.create(binding);
            CommandReceipt receipt = appendBindingEvent(
                    trusted, commandId, committed, "GITHUB_PROVIDER_BOUND", now);
            return CommandExecution.completed(bindingView(committed), receipt);
        });
    }

    public List<GitHubProviderBindingView> listBindings(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            TeamId teamId) {
        Connection connection = requireVisibleConnection(context, organizationId, connectionId);
        requireBindingAuthority(context, connection.owner(), teamId, timeProvider.now());
        Team team = requireTeam(organizationId, teamId);
        return bindings.findCandidates(new ProviderBindingQuery(
                        organizationId,
                        team.id(),
                        team.defaultWorkspaceId(),
                        Optional.empty(),
                        connection.owner(),
                        ProviderType.SOURCE_CODE,
                        Optional.of(executionIdentity(connection.owner().type())))).stream()
                .filter(value -> value.connectionId().filter(connection.id()::equals).isPresent())
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .map(GitHubConnectionApplicationService::bindingView)
                .toList();
    }

    /** Verifies remote identity using only current server-resolved Connection and Grant facts. */
    public GitHubConnectionView verify(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId) {
        AccessFacts facts = requireManagedAccess(
                context, organizationId, connectionId, expectedVersion, correlationId);
        provider.verifyConnection(new VerifyGitHubConnectionRequest(
                facts.request(), facts.authenticationType(), facts.policy()));
        Connection current = requireConnection(organizationId, connectionId);
        return view(current, currentGrant(current).orElse(null), context.actor().id());
    }

    /** Refreshes the full remote Catalog; Provider network I/O occurs outside a DB transaction. */
    public List<GitHubRepositoryView> synchronizeCatalog(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId) {
        AccessFacts facts = requireManagedAccess(
                context, organizationId, connectionId, expectedVersion, correlationId);
        GitHubCatalogResult result = provider.synchronizeCatalog(
                new SyncGitHubCatalogRequest(facts.request(), facts.policy()));
        return result.deliverableRepositories().stream().map(GitHubRepositoryView::from).toList();
    }

    /** Returns only current DELIVERABLE entries after rechecking owner visibility. */
    public List<GitHubRepositoryView> listCatalog(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        Connection connection = requireVisibleConnection(context, organizationId, connectionId);
        UtcTimestamp now = timeProvider.now();
        requireCurrentProfile(connection);
        return githubRepository.findDeliverableRepositories(organizationId, connectionId).stream()
                .filter(value -> value.connectionVersion() == connection.version())
                .filter(value -> value.status() == GitHubRepositoryStatus.DELIVERABLE)
                .filter(value -> value.isCurrentAt(now))
                .sorted(Comparator.comparing(GitHubRepositoryCatalogEntry::fullName))
                .map(GitHubRepositoryView::from)
                .toList();
    }

    /** Revalidates one stable Repository ID; branch and authorization versions come from storage. */
    public GitHubRemotePreflightView preflightRepository(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            ProviderBindingId bindingId,
            String externalRepositoryId,
            UUID correlationId) {
        Connection connection = requireVisibleConnection(context, organizationId, connectionId);
        ProviderBinding binding = bindings.findById(
                        organizationId, Objects.requireNonNull(bindingId, "bindingId"))
                .filter(value -> value.connectionId().filter(connectionId::equals).isPresent())
                .filter(value -> value.connectionVersion().filter(version -> version == expectedVersion).isPresent())
                .orElseThrow(() -> new AggregateNotFoundException("ProviderBinding", bindingId));
        requireBindingAuthority(context, connection.owner(), binding.target().teamId(), timeProvider.now());
        ProviderAccessScope currentBindingAccess = requireCurrentBindingAccess(
                binding, connection, timeProvider.now());
        AccessFacts facts = requireManagedAccess(
                context,
                organizationId,
                connectionId,
                expectedVersion,
                correlationId,
                currentBindingAccess);
        GitHubRepositoryCatalogEntry catalog = githubRepository
                .findRepository(organizationId, connectionId, externalRepositoryId)
                .filter(value -> value.connectionVersion() == facts.connection().version())
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.RESOURCE_UNAVAILABLE,
                        "GitHub repository is unavailable"));
        GitHubRepositoryPreflightResult result = provider.preflightRepository(
                new PreflightGitHubRepositoryRequest(
                        facts.request(),
                        catalog.externalRepositoryId(),
                        catalog.defaultBranch(),
                        facts.policy()));
        return GitHubRemotePreflightView.from(result);
    }

    public GitHubAuthorizationHealthView health(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        Connection connection = requireVisibleConnection(context, organizationId, connectionId);
        UtcTimestamp now = timeProvider.now();
        Optional<ConnectionGrant> grant = currentGrant(connection);
        Optional<CredentialDescriptor> credential = describeCredential(connection, context.actor().id());
        boolean connectionUsable = connection.isUsableAt(now);
        boolean grantUsable = grant.flatMap(value -> value.effectiveAccess(
                        value.grantedAccess(), connection, now)).isPresent();
        boolean credentialUsable = credential.filter(value -> value.isUsableAt(now)).isPresent();
        boolean profileCurrent = githubRepository
                .findProfile(organizationId, connectionId, connection.version())
                .filter(value -> value.isCurrentFor(connection.version()))
                .isPresent();
        int repositories = profileCurrent
                ? (int) githubRepository.findDeliverableRepositories(organizationId, connectionId).stream()
                        .filter(value -> value.connectionVersion() == connection.version())
                        .filter(value -> value.isCurrentAt(now))
                        .count()
                : 0;
        Optional<GitHubAuthorizationHealthView.GitHubRateLimitHealth> rate = githubRepository
                .findCurrentRateLimit(organizationId, connectionId, "core")
                .filter(value -> value.connectionVersion() == connection.version())
                .map(value -> new GitHubAuthorizationHealthView.GitHubRateLimitHealth(
                        value.resource(),
                        value.limit(),
                        value.remaining(),
                        value.resetsAt(),
                        value.observedAt()));
        String status = authorizationStatus(
                connectionUsable, grantUsable, credentialUsable, profileCurrent, rate, now);
        return new GitHubAuthorizationHealthView(
                status,
                connectionUsable,
                grantUsable,
                credentialUsable,
                profileCurrent,
                repositories,
                policySettings.webhookReceiverConfigured() ? "CONFIGURED" : "NOT_CONFIGURED",
                rate);
    }

    /** Revokes Grant, Connection and encrypted Credential in one irreversible transaction. */
    public CommandExecution<GitHubConnectionView> revoke(
            TeamCommandContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            String reason) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        String terminalReason = requireReason(reason);
        CommandRequestHash hash = CommandRequestHash.sha256(
                REVOKE_CONNECTION,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                connectionId.toString(),
                Long.toString(expectedVersion),
                terminalReason);
        UUID commandId = UUID.randomUUID();
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Connection current = requireVisibleConnection(
                    trusted.access(), organizationId, connectionId);
            requireManageOwner(trusted.access(), current.owner(), now);
            CommandReservation reservation = reserve(
                    trusted, organizationId, REVOKE_CONNECTION, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            if (current.version() != expectedVersion) {
                current.revoke(expectedVersion, trusted.access().actor(), terminalReason, now);
            }
            ConnectionGrant grant = requireGrant(current);
            CredentialDescriptor credential = describeCredential(
                            current, trusted.access().actor().id())
                    .orElseThrow(() -> new DomainValidationException(
                            "githubConnection.credential", "is unavailable"));
            grants.update(grant.revoke(
                    grant.version(), trusted.access().actor(), terminalReason, now));
            credentials.revoke(
                    credential.reference(),
                    credential.version(),
                    new CredentialMutationContext(
                            organizationId, trusted.access().actor().id()),
                    CredentialRevocationReason.CONNECTION_REVOKED);
            Connection revoked = connections.update(current.revoke(
                    expectedVersion, trusted.access().actor(), terminalReason, now));
            CommandReceipt receipt = appendConnectionEvent(
                    trusted, commandId, revoked, "GITHUB_CONNECTION_REVOKED", now);
            return CommandExecution.completed(
                    view(revoked, null, trusted.access().actor().id()), receipt);
        });
    }

    private AccessFacts requireManagedAccess(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId) {
        return requireManagedAccess(
                context,
                organizationId,
                connectionId,
                expectedVersion,
                correlationId,
                null);
    }

    private AccessFacts requireManagedAccess(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId,
            ProviderAccessScope bindingAccess) {
        Connection connection = requireVisibleConnection(context, organizationId, connectionId);
        requireManageOwner(context, connection.owner(), timeProvider.now());
        if (connection.version() != expectedVersion) {
            // Let the aggregate produce the canonical optimistic-lock domain error shape.
            connection.suspend(expectedVersion, context.actor(), timeProvider.now());
        }
        ConnectionGrant grant = requireGrant(connection);
        ProviderAccessScope requestedAccess = bindingAccess == null
                ? grant.grantedAccess()
                : grant.effectiveAccess(bindingAccess, connection, timeProvider.now())
                        .orElseThrow(() -> new GitHubProviderException(
                                GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                                "GitHub Provider Binding authorization is unavailable"));
        GitHubRepositoryPolicy policy = policyFor(grant);
        GitHubAuthenticationType authentication = authenticationType(connection.owner().type());
        return new AccessFacts(
                connection,
                grant,
                authentication,
                policy,
                new GitHubAccessRequest(
                        organizationId,
                        connection.id(),
                        connection.version(),
                        grant.id(),
                        grant.version(),
                        grant.grantee(),
                        requestedAccess,
                        context.actor().id(),
                        Objects.requireNonNull(correlationId, "correlationId")));
    }

    private GitHubConnectionView view(
            Connection connection, ConnectionGrant grant, io.crewscope.domain.shared.id.PrincipalId actor) {
        Optional<GitHubConnectionProfile> profile = githubRepository.findProfile(
                connection.organizationId(), connection.id(), connection.version());
        Optional<CredentialDescriptor> credential = describeCredential(connection, actor);
        List<String> repositoryAllowlist = grant == null
                ? List.of()
                : repositoryAllowlist(grant.grantedAccess().resources()).stream().sorted().toList();
        return new GitHubConnectionView(
                connection.id().toString(),
                connection.owner().type(),
                connection.owner().teamId(),
                authenticationType(connection.owner().type()),
                profile.map(GitHubConnectionProfile::externalIdentity),
                profile.map(GitHubConnectionProfile::externalAccountLogin),
                connection.status(),
                connection.version(),
                repositoryAllowlist,
                credential.map(CredentialDescriptor::status),
                connection.expiresAt(),
                profile.map(value -> value.audit().updatedAt()),
                connection.audit().createdAt(),
                connection.audit().updatedAt());
    }

    private Optional<CredentialDescriptor> describeCredential(
            Connection connection, io.crewscope.domain.shared.id.PrincipalId actor) {
        return credentials.describe(
                new CredentialReference(connection.organizationId(), connection.credentialId()),
                new CredentialAccessContext(
                        connection.organizationId(),
                        actor,
                        Set.of(connection.credentialId()),
                        "github:connection:metadata"));
    }

    private Optional<ConnectionGrant> currentGrant(Connection connection) {
        List<ConnectionGrant> candidates = grants
                .findByConnectionAndGrantee(connection.id(), connection.owner()).stream()
                .filter(value -> value.connectionOwner().equals(connection.owner()))
                .filter(value -> value.status() == ConnectionGrantStatus.ACTIVE)
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private ConnectionGrant requireGrant(Connection connection) {
        return currentGrant(connection).orElseThrow(() -> new GitHubProviderException(
                GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                "GitHub Connection Grant is unavailable"));
    }

    private GitHubConnectionProfile requireCurrentProfile(Connection connection) {
        return githubRepository
                .findProfile(connection.organizationId(), connection.id(), connection.version())
                .filter(value -> value.isCurrentFor(connection.version()))
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Connection has not been verified"));
    }

    private GitHubRepositoryPolicy policyFor(ConnectionGrant grant) {
        return policySettings.policyFor(repositoryAllowlist(grant.grantedAccess().resources()));
    }

    private static Set<String> repositoryAllowlist(ProviderResourceScope resources) {
        if (resources.unrestricted()) {
            throw new DomainValidationException(
                    "githubConnection.repositoryAllowlist",
                    "must be explicit for a managed GitHub Connection");
        }
        Set<String> result = resources.resources().stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(REPOSITORY_RESOURCE_PREFIX))
                .map(value -> value.substring(REPOSITORY_RESOURCE_PREFIX.length()))
                .collect(Collectors.toSet());
        if (result.size() != resources.resources().size() || result.isEmpty()) {
            throw new DomainValidationException(
                    "githubConnection.repositoryAllowlist",
                    "contains an invalid GitHub Repository resource");
        }
        return Set.copyOf(result);
    }

    private static ProviderAccessScope access(Set<String> repositories) {
        GitHubRepositoryPolicy normalized = new GitHubRepositoryPolicy(
                repositories, Set.of(), true, true, false);
        String[] resources = normalized.repositoryAllowlist().stream()
                .map(value -> REPOSITORY_RESOURCE_PREFIX + value)
                .toArray(String[]::new);
        return new ProviderAccessScope(
                DELIVERY_CAPABILITIES, ProviderResourceScope.of(resources));
    }

    private OwnerFacts resolveCreateOwner(
            TeamAccessContext context, CreateGitHubConnectionRequest request, UtcTimestamp now) {
        Principal actor = context.actor();
        return switch (request.authenticationType()) {
            case APP_INSTALLATION -> {
                TeamId teamId = request.teamId().orElseThrow(() -> new DomainValidationException(
                        "githubConnection.teamId", "is required for APP_INSTALLATION"));
                Team team = requireTeam(actor.scope().organizationId(), teamId);
                requireTeamManage(context, team, now);
                CredentialSubject subject = switch (request.credentialSubjectType()) {
                    case TEAM -> CredentialSubject.team(team.organizationId(), team.id());
                    case ORGANIZATION -> {
                        if (!context.platformAdministrator()) {
                            throw new PolicyDeniedException(
                                    "use an Organization credential for a Team GitHub Connection");
                        }
                        yield CredentialSubject.organization(team.organizationId());
                    }
                    case PRINCIPAL -> throw new DomainValidationException(
                            "githubConnection.credentialSubjectType",
                            "must be TEAM or ORGANIZATION for APP_INSTALLATION");
                };
                yield new OwnerFacts(ProviderOwner.team(team), subject);
            }
            case OAUTH_USER -> {
                if (request.teamId().isPresent()
                        || request.credentialSubjectType() != CredentialSubjectType.PRINCIPAL) {
                    throw new DomainValidationException(
                            "githubConnection.owner",
                            "OAUTH_USER requires USER ownership and a PRINCIPAL credential");
                }
                yield new OwnerFacts(
                        ProviderOwner.user(actor),
                        CredentialSubject.principal(actor.scope().organizationId(), actor.id()));
            }
        };
    }

    private ProviderOwner resolveListOwner(
            TeamAccessContext context,
            OrganizationId organizationId,
            ProviderOwnerType ownerType,
            Optional<TeamId> teamId) {
        Principal actor = requireOrganizationUser(context, organizationId);
        return switch (Objects.requireNonNull(ownerType, "ownerType")) {
            case USER -> {
                if (teamId.isPresent()) {
                    throw new DomainValidationException("githubConnection.teamId", "must be absent for USER");
                }
                yield ProviderOwner.user(actor);
            }
            case TEAM -> {
                Team team = requireTeam(organizationId, teamId.orElseThrow(() ->
                        new DomainValidationException("githubConnection.teamId", "is required for TEAM")));
                requireActiveMember(actor, team);
                yield ProviderOwner.team(team);
            }
            case ORGANIZATION -> throw new DomainValidationException(
                    "githubConnection.ownerType", "ORGANIZATION GitHub Connections are not supported");
        };
    }

    private Connection requireVisibleConnection(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        requireOrganizationUser(context, organizationId);
        Connection connection = requireConnection(organizationId, connectionId);
        requireViewOwner(context, connection.owner());
        return connection;
    }

    private Connection requireConnection(OrganizationId organizationId, ConnectionId connectionId) {
        return connections.findById(organizationId, Objects.requireNonNull(connectionId, "connectionId"))
                .filter(value -> CONNECTOR_KEY.equals(value.connectorKey()))
                .orElseThrow(() -> new AggregateNotFoundException("Connection", connectionId));
    }

    private void requireViewOwner(TeamAccessContext context, ProviderOwner owner) {
        switch (owner.type()) {
            case USER -> {
                if (!owner.userPrincipalId().orElseThrow().equals(context.actor().id())) {
                    throw new PolicyDeniedException("view this GitHub Connection");
                }
            }
            case TEAM -> requireActiveMember(
                    context.actor(), requireTeam(owner.organizationId(), owner.teamId().orElseThrow()));
            case ORGANIZATION -> throw new PolicyDeniedException("view this GitHub Connection");
        }
    }

    private void requireManageOwner(TeamAccessContext context, ProviderOwner owner, UtcTimestamp now) {
        switch (owner.type()) {
            case USER -> {
                if (!owner.userPrincipalId().orElseThrow().equals(context.actor().id())) {
                    throw new PolicyDeniedException("manage this GitHub Connection");
                }
            }
            case TEAM -> requireTeamManage(
                    context, requireTeam(owner.organizationId(), owner.teamId().orElseThrow()), now);
            case ORGANIZATION -> throw new PolicyDeniedException("manage this GitHub Connection");
        }
    }

    private void requireTeamManage(TeamAccessContext context, Team team, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        TeamMember member = requireActiveMember(context.actor(), team);
        Map<TeamRoleId, TeamRole> rolesById = roles.findByTeam(team.organizationId(), team.id()).stream()
                .collect(Collectors.toMap(TeamRole::id, value -> value));
        boolean allowed = memberRoles.findByMember(team.organizationId(), member.id()).stream()
                .filter(value -> value.status() == MemberRoleStatus.ACTIVE)
                .filter(value -> value.isEffectiveAt(now))
                .filter(value -> value.roleScope().equals(RoleScope.team()))
                .map(value -> rolesById.get(value.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(value -> value.permissions().contains(TeamPermission.PROVIDER_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage Team GitHub Connections");
        }
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        return teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    }

    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(value -> value.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's GitHub Connections"));
    }

    private void requireBindingAuthority(
            TeamAccessContext context, ProviderOwner owner, TeamId targetTeamId, UtcTimestamp now) {
        Team targetTeam = requireTeam(owner.organizationId(), targetTeamId);
        if (owner.type() == ProviderOwnerType.TEAM) {
            if (owner.teamId().filter(targetTeamId::equals).isEmpty()) {
                throw new PolicyDeniedException("bind a Team GitHub Connection across Teams");
            }
            requireTeamManage(context, targetTeam, now);
            return;
        }
        if (owner.type() == ProviderOwnerType.USER
                && owner.userPrincipalId().filter(context.actor().id()::equals).isPresent()) {
            requireActiveMember(context.actor(), targetTeam);
            return;
        }
        throw new PolicyDeniedException("bind this GitHub Connection");
    }

    private ProviderFoundationFacts providerFoundation(
            OrganizationId organizationId, Principal actor, UtcTimestamp now) {
        providerBootstrapLock.acquire(organizationId);
        ProviderDefinition candidateDefinition = ProviderDefinition.create(
                githubRegistration.definitionId(organizationId),
                organizationId,
                githubRegistration.definitionKey(),
                githubRegistration.type(),
                githubRegistration.interfaceVersion(),
                githubRegistration.displayName(),
                githubRegistration.capabilities(),
                actor,
                now);
        ProviderDefinition definition = definitions
                .findByKey(organizationId, githubRegistration.definitionKey())
                .orElseGet(() -> definitions.create(candidateDefinition));
        if (!definition.id().equals(candidateDefinition.id())
                || !definition.capabilities().equals(DELIVERY_CAPABILITIES)
                || definition.type() != ProviderType.SOURCE_CODE
                || !definition.isActive()) {
            throw new DomainValidationException(
                    "githubProvider.definition", "conflicts with the built-in contract");
        }
        List<ProviderImplementation> sameKey = implementations
                .findByDefinition(organizationId, definition.id()).stream()
                .filter(value -> value.key().equals(githubRegistration.implementationKey()))
                .toList();
        if (sameKey.size() > 1) {
            throw new DomainValidationException(
                    "githubProvider.implementation", "contains ambiguous registrations");
        }
        ProviderImplementation implementation = sameKey.isEmpty()
                ? implementations.create(ProviderImplementation.create(
                        githubRegistration.implementationId(organizationId),
                        definition,
                        githubRegistration.implementationKey(),
                        githubRegistration.implementationVersion(),
                        githubRegistration.capabilities(),
                        ProviderConnectionRequirement.REQUIRED,
                        Optional.of(CONNECTOR_KEY),
                        actor,
                        now))
                : sameKey.get(0);
        if (!implementation.supports(definition, DELIVERY_CAPABILITIES)
                || implementation.connectionRequirement() != ProviderConnectionRequirement.REQUIRED
                || implementation.connectorKey().filter(CONNECTOR_KEY::equals).isEmpty()) {
            throw new DomainValidationException(
                    "githubProvider.implementation", "conflicts with the built-in contract");
        }
        return new ProviderFoundationFacts(definition, implementation);
    }

    private ProviderAccessScope requireCurrentBindingAccess(
            ProviderBinding binding, Connection connection, UtcTimestamp now) {
        ConnectionGrant grant = requireGrant(connection);
        if (!binding.owner().equals(connection.owner())
                || binding.providerType() != ProviderType.SOURCE_CODE
                || binding.connectionGrantId().filter(grant.id()::equals).isEmpty()
                || binding.connectionGrantVersion().filter(version -> version == grant.version()).isEmpty()
                || binding.executionIdentity()
                        .filter(executionIdentity(connection.owner().type())::equals)
                        .isEmpty()) {
            throw new GitHubProviderException(
                    GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                    "GitHub Provider Binding authorization is unavailable");
        }
        ProviderDefinition definition = definitions
                .findById(connection.organizationId(), binding.definitionId())
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Provider definition is unavailable"));
        ProviderImplementation implementation = implementations
                .findById(connection.organizationId(), binding.implementationId())
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.CONNECTION_UNAVAILABLE,
                        "GitHub Provider implementation is unavailable"));
        return binding.currentAccess(
                        definition,
                        implementation,
                        Optional.of(connection),
                        Optional.of(grant),
                        now)
                .orElseThrow(() -> new GitHubProviderException(
                        GitHubProviderErrorCode.GRANT_UNAVAILABLE,
                        "GitHub Provider Binding is stale"));
    }

    private static GitHubProviderBindingView bindingView(ProviderBinding binding) {
        return new GitHubProviderBindingView(
                binding.id().toString(),
                binding.target().teamId(),
                binding.target().workspaceId(),
                binding.connectionId().orElseThrow().toString(),
                binding.connectionVersion().orElseThrow(),
                binding.connectionGrantId().map(Object::toString).orElse(null),
                binding.connectionGrantVersion().orElse(0L),
                binding.executionIdentity().orElseThrow(),
                repositoryAllowlist(binding.effectiveAccess().resources()).stream().sorted().toList(),
                binding.status(),
                binding.defaultUsage(),
                binding.version());
    }

    private static ProviderExecutionIdentity executionIdentity(ProviderOwnerType ownerType) {
        return switch (ownerType) {
            case USER -> ProviderExecutionIdentity.DELEGATED_USER;
            case TEAM -> ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT;
            case ORGANIZATION -> ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT;
        };
    }

    private static Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        Principal actor = trusted.actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    private static GitHubAuthenticationType authenticationType(ProviderOwnerType ownerType) {
        return switch (ownerType) {
            case TEAM -> GitHubAuthenticationType.APP_INSTALLATION;
            case USER -> GitHubAuthenticationType.OAUTH_USER;
            case ORGANIZATION -> throw new DomainValidationException(
                    "githubConnection.ownerType", "ORGANIZATION ownership is not supported");
        };
    }

    private static String authorizationStatus(
            boolean connection,
            boolean grant,
            boolean credential,
            boolean profile,
            Optional<GitHubAuthorizationHealthView.GitHubRateLimitHealth> rate,
            UtcTimestamp now) {
        if (!connection) return "CONNECTION_UNAVAILABLE";
        if (!grant) return "GRANT_UNAVAILABLE";
        if (!credential) return "CREDENTIAL_UNAVAILABLE";
        if (!profile) return "VERIFICATION_REQUIRED";
        if (rate.filter(value -> value.remaining() == 0 && value.resetsAt().compareTo(now) > 0).isPresent()) {
            return "RATE_LIMITED";
        }
        return "HEALTHY";
    }

    private static void requirePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new DomainValidationException("pagination", "offset or limit is invalid");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new DomainValidationException("githubConnection.revokeReason", "is invalid");
        }
        return reason.strip();
    }

    private CommandReservation reserve(
            TeamCommandContext context,
            OrganizationId organizationId,
            String commandType,
            CommandRequestHash hash,
            UUID commandId,
            UtcTimestamp now) {
        return receipts.reserve(new CommandReservationRequest(
                organizationId,
                context.idempotencyKey(),
                commandType,
                hash,
                commandId,
                context.correlationId(),
                now));
    }

    private CommandReceipt appendConnectionEvent(
            TeamCommandContext context,
            UUID commandId,
            Connection connection,
            String eventType,
            UtcTimestamp now) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<ConnectionLifecycleChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                connection.organizationId(),
                connection.owner().teamId(),
                Optional.empty(),
                AggregateReference.of(CONNECTION_AGGREGATE, connection.id()),
                connection.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                ConnectionLifecycleChanged.from(connection));
        eventStore.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return completeReceipt(
                context,
                connection.organizationId(),
                commandId,
                eventId,
                connection.version(),
                now);
    }

    private CommandReceipt appendBindingEvent(
            TeamCommandContext context,
            UUID commandId,
            ProviderBinding binding,
            String eventType,
            UtcTimestamp now) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<ProviderBindingChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                binding.organizationId(),
                Optional.of(binding.target().teamId()),
                Optional.of(binding.target().workspaceId()),
                AggregateReference.of(BINDING_AGGREGATE, binding.id()),
                binding.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                now,
                ProviderBindingChanged.from(binding));
        eventStore.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        return completeReceipt(
                context,
                binding.organizationId(),
                commandId,
                eventId,
                binding.version(),
                now);
    }

    private CommandReceipt completeReceipt(
            TeamCommandContext context,
            OrganizationId organizationId,
            UUID commandId,
            UUID eventId,
            long version,
            UtcTimestamp now) {
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, version, context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        return receipt;
    }

    private static String secretFingerprint(CredentialSecret secret) {
        byte[] bytes = secret.copyBytes();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private record OwnerFacts(ProviderOwner owner, CredentialSubject credentialSubject) {}

    private record AccessFacts(
            Connection connection,
            ConnectionGrant grant,
            GitHubAuthenticationType authenticationType,
            GitHubRepositoryPolicy policy,
            GitHubAccessRequest request) {}

    private record ProviderFoundationFacts(
            ProviderDefinition definition, ProviderImplementation implementation) {}
}
