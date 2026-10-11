package io.crewscope.application.github;

import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingDefaultConflictException;
import io.crewscope.application.provider.ProviderBindingQuery;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.workspace.Workspace;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Secure application boundary for GitHub Connection, Catalog and remote Preflight APIs.
 * Since M11-E01 this class stays the single public orchestrator: authorization gates
 * ({@link GitHubConnectionAuthorizationService}), the command journal
 * ({@link GitHubConnectionCommandJournal}), Provider bootstrap
 * ({@link GitHubProviderFoundationService}) and access-fact revalidation
 * ({@link GitHubConnectionAccessService}) are injected collaborators with
 * unchanged behavior.
 */
public final class GitHubConnectionApplicationService {

    public static final String CONNECTOR_KEY = "github-source-code";
    public static final String CREDENTIAL_TYPE = "GITHUB_TOKEN";
    public static final ProviderCapabilities DELIVERY_CAPABILITIES = ProviderCapabilities.of(
            "source.repository.catalog",
            "source.repository.read",
            "source.repository.push",
            "source.pull-request.create");
    private static final String CREATE_CONNECTION = "CREATE_GITHUB_CONNECTION";
    private static final String BIND_CONNECTION = "BIND_GITHUB_CONNECTION";
    private static final String REVOKE_CONNECTION = "REVOKE_GITHUB_CONNECTION";

    private final ConnectionRepository connections;
    private final ConnectionGrantRepository grants;
    private final CredentialStore credentials;
    private final GitHubProviderRepository githubRepository;
    private final GitHubProviderPort provider;
    private final WorkspaceRepository workspaces;
    private final ProviderBindingRepository bindings;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final GitHubConnectionPolicySettings policySettings;
    private final GitHubConnectionAuthorizationService authorization;
    private final GitHubConnectionCommandJournal journal;
    private final GitHubProviderFoundationService foundationService;
    private final GitHubConnectionAccessService access;

    public GitHubConnectionApplicationService(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            GitHubProviderRepository githubRepository,
            GitHubProviderPort provider,
            WorkspaceRepository workspaces,
            ProviderBindingRepository bindings,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            GitHubConnectionPolicySettings policySettings,
            GitHubConnectionAuthorizationService authorization,
            GitHubConnectionCommandJournal journal,
            GitHubProviderFoundationService foundationService,
            GitHubConnectionAccessService access) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.githubRepository = Objects.requireNonNull(githubRepository, "githubRepository");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.policySettings = Objects.requireNonNull(policySettings, "policySettings");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.foundationService = Objects.requireNonNull(foundationService, "foundationService");
        this.access = Objects.requireNonNull(access, "access");
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
            ProviderAccessScope grantedAccess = GitHubRepositoryResources.access(
                    policy.repositoryAllowlist());
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
                Principal actor = GitHubConnectionAuthorizationService.requireOrganizationUser(
                        trusted.access(), organizationId);
                GitHubConnectionOwnerFacts ownerFacts =
                        authorization.resolveCreateOwner(trusted.access(), required, now);
                CommandReservation reservation = journal.reserve(
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
                        required.externalAccountId().isBlank()
                                ? "pending:" + connectionId.value()
                                : required.externalAccountId(),
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
                CommandReceipt receipt = journal.appendConnectionEvent(
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
        ProviderOwner owner = authorization.resolveListOwner(
                context, organizationId, ownerType, teamId);
        return connections.findByOwner(owner).stream()
                .filter(value -> CONNECTOR_KEY.equals(value.connectorKey()))
                .sorted(Comparator.comparing(value -> value.audit().createdAt(), Comparator.reverseOrder()))
                .skip(offset)
                .limit(limit)
                .map(value -> view(value, access.currentGrant(value).orElse(null), context.actor().id()))
                .toList();
    }

    public GitHubConnectionView get(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        Connection connection = authorization.requireVisibleConnection(
                context, organizationId, connectionId);
        return view(connection, access.currentGrant(connection).orElse(null), context.actor().id());
    }

    /** Binds a verified Connection to one active Team Workspace with immutable authorization pins. */
    public CommandExecution<GitHubProviderBindingView> bind(
            TeamCommandContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedConnectionVersion,
            TeamId teamId,
            boolean defaultUsage) {
        return bind(context, organizationId, connectionId, expectedConnectionVersion, teamId,
                defaultUsage, Optional.empty());
    }

    /**
     * Binds a connection to the selected catalog repository resources.  The
     * legacy overload remains available for explicit allowlist connections;
     * discovery connections must provide a concrete selection.
     */
    public CommandExecution<GitHubProviderBindingView> bind(
            TeamCommandContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedConnectionVersion,
            TeamId teamId,
            boolean defaultUsage,
            Optional<Set<String>> selectedRepositoryIds) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CommandRequestHash hash = CommandRequestHash.sha256(
                BIND_CONNECTION,
                trusted.access().actor().id().toString(),
                organizationId.toString(),
                connectionId.toString(),
                Long.toString(expectedConnectionVersion),
                teamId.toString(),
                Boolean.toString(defaultUsage),
                selectedRepositoryIds
                        .map(value -> value.stream().sorted().collect(Collectors.joining("\n")))
                        .orElse(""));
        UUID commandId = UUID.randomUUID();
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Connection connection = authorization.requireVisibleConnection(
                    trusted.access(), organizationId, connectionId);
            authorization.requireBindingAuthority(trusted.access(), connection.owner(), teamId, now);
            if (connection.version() != expectedConnectionVersion) {
                connection.suspend(expectedConnectionVersion, trusted.access().actor(), now);
            }
            access.requireCurrentProfile(connection);
            CommandReservation reservation = journal.reserve(
                    trusted, organizationId, BIND_CONNECTION, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            ConnectionGrant grant = access.requireGrant(connection);
            Team team = authorization.requireTeam(organizationId, teamId);
            Workspace workspace = workspaces.findById(organizationId, team.defaultWorkspaceId())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "Workspace", team.defaultWorkspaceId()));
            GitHubProviderFoundationFacts foundation = foundationService.providerFoundation(
                    organizationId, trusted.access().actor(), now);
            ProviderBindingTarget target = ProviderBindingTarget.workspace(workspace);
            List<ProviderBinding> existing = bindings.findCandidates(new ProviderBindingQuery(
                            organizationId,
                            team.id(),
                            workspace.id(),
                            Optional.empty(),
                            connection.owner(),
                            ProviderType.SOURCE_CODE,
                            Optional.of(GitHubConnectionAuthorizationService.executionIdentity(
                                    connection.owner().type())))).stream()
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
            if (defaultUsage) {
                // The level already holding an ACTIVE default (any Connection) is rejected here
                // so the client receives the conflict with the existing binding id, instead of
                // the database constraint leaking as a retryable internal error (M9b-Q02
                // defect 23). Demote the current default or bind non-default, then replace.
                bindings.findActiveWorkspaceDefaults(
                                organizationId, team.id(), workspace.id(),
                                connection.owner(), ProviderType.SOURCE_CODE)
                        .stream()
                        .findFirst()
                        .ifPresent(currentDefault -> {
                            throw new ProviderBindingDefaultConflictException(
                                    currentDefault.id());
                        });
            }
            ProviderAccessScope bindingAccess = selectedRepositoryIds
                    .filter(value -> !value.isEmpty())
                    .map(ids -> access.selectedRepositoryAccess(
                            organizationId, connectionId, connection, grant, ids, now))
                    .orElseGet(grant::grantedAccess);
            if (grant.grantedAccess().resources().unrestricted()
                    && bindingAccess.resources().unrestricted()) {
                throw new DomainValidationException(
                        "githubProviderBinding.repositoryIds",
                        "must select at least one GitHub repository");
            }
            ProviderBinding binding = ProviderBinding.bind(
                    ProviderBindingId.generate(),
                    target,
                    connection.owner(),
                    foundation.definition(),
                    foundation.implementation(),
                    Optional.of(connection),
                    Optional.of(grant),
                    bindingAccess,
                    defaultUsage,
                    trusted.access().actor(),
                    now);
            ProviderBinding committed = bindings.create(binding);
            CommandReceipt receipt = journal.appendBindingEvent(
                    trusted, commandId, committed, "GITHUB_PROVIDER_BOUND", now);
            return CommandExecution.completed(bindingView(committed), receipt);
        });
    }

    public List<GitHubProviderBindingView> listBindings(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            TeamId teamId) {
        Connection connection = authorization.requireVisibleConnection(
                context, organizationId, connectionId);
        authorization.requireBindingAuthority(
                context, connection.owner(), teamId, timeProvider.now());
        Team team = authorization.requireTeam(organizationId, teamId);
        return bindings.findCandidates(new ProviderBindingQuery(
                        organizationId,
                        team.id(),
                        team.defaultWorkspaceId(),
                        Optional.empty(),
                        connection.owner(),
                        ProviderType.SOURCE_CODE,
                        Optional.of(GitHubConnectionAuthorizationService.executionIdentity(
                                connection.owner().type())))).stream()
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
        GitHubConnectionAccessFacts facts = access.requireManagedAccess(
                context, organizationId, connectionId, expectedVersion, correlationId);
        provider.verifyConnection(new VerifyGitHubConnectionRequest(
                facts.request(), facts.authenticationType(), facts.policy()));
        Connection current = authorization.requireConnection(organizationId, connectionId);
        return view(current, access.currentGrant(current).orElse(null), context.actor().id());
    }

    /** Refreshes the full remote Catalog; Provider network I/O occurs outside a DB transaction. */
    public List<GitHubRepositoryView> synchronizeCatalog(
            TeamAccessContext context,
            OrganizationId organizationId,
            ConnectionId connectionId,
            long expectedVersion,
            UUID correlationId) {
        GitHubConnectionAccessFacts facts = access.requireManagedAccess(
                context, organizationId, connectionId, expectedVersion, correlationId);
        GitHubCatalogResult result = provider.synchronizeCatalog(
                new SyncGitHubCatalogRequest(facts.request(), facts.policy()));
        return result.deliverableRepositories().stream().map(GitHubRepositoryView::from).toList();
    }

    /** Returns only current DELIVERABLE entries after rechecking owner visibility. */
    public List<GitHubRepositoryView> listCatalog(
            TeamAccessContext context, OrganizationId organizationId, ConnectionId connectionId) {
        Connection connection = authorization.requireVisibleConnection(
                context, organizationId, connectionId);
        UtcTimestamp now = timeProvider.now();
        access.requireCurrentProfile(connection);
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
        Connection connection = authorization.requireVisibleConnection(
                context, organizationId, connectionId);
        ProviderBinding binding = bindings.findById(
                        organizationId, Objects.requireNonNull(bindingId, "bindingId"))
                .filter(value -> value.connectionId().filter(connectionId::equals).isPresent())
                .filter(value -> value.connectionVersion().filter(version -> version == expectedVersion).isPresent())
                .orElseThrow(() -> new AggregateNotFoundException("ProviderBinding", bindingId));
        authorization.requireBindingAuthority(
                context, connection.owner(), binding.target().teamId(), timeProvider.now());
        ProviderAccessScope currentBindingAccess = access.requireCurrentBindingAccess(
                binding, connection, timeProvider.now());
        GitHubConnectionAccessFacts facts = access.requireManagedAccess(
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
        Connection connection = authorization.requireVisibleConnection(
                context, organizationId, connectionId);
        UtcTimestamp now = timeProvider.now();
        Optional<ConnectionGrant> grant = access.currentGrant(connection);
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
            Connection current = authorization.requireVisibleConnection(
                    trusted.access(), organizationId, connectionId);
            authorization.requireManageOwner(trusted.access(), current.owner(), now);
            CommandReservation reservation = journal.reserve(
                    trusted, organizationId, REVOKE_CONNECTION, hash, commandId, now);
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            if (current.version() != expectedVersion) {
                current.revoke(expectedVersion, trusted.access().actor(), terminalReason, now);
            }
            ConnectionGrant grant = access.requireGrant(current);
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
            CommandReceipt receipt = journal.appendConnectionEvent(
                    trusted, commandId, revoked, "GITHUB_CONNECTION_REVOKED", now);
            return CommandExecution.completed(
                    view(revoked, null, trusted.access().actor().id()), receipt);
        });
    }

    private GitHubConnectionView view(
            Connection connection, ConnectionGrant grant, io.crewscope.domain.shared.id.PrincipalId actor) {
        Optional<GitHubConnectionProfile> profile = githubRepository.findProfile(
                connection.organizationId(), connection.id(), connection.version());
        Optional<CredentialDescriptor> credential = describeCredential(connection, actor);
        List<String> repositoryAllowlist = grant == null
                ? List.of()
                : GitHubRepositoryResources.repositoryAllowlist(
                        grant.grantedAccess().resources()).stream().sorted().toList();
        return new GitHubConnectionView(
                connection.id().toString(),
                connection.owner().type(),
                connection.owner().teamId(),
                GitHubConnectionAuthorizationService.authenticationType(connection.owner().type()),
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
                GitHubRepositoryResources.repositoryAllowlist(
                        binding.effectiveAccess().resources()).stream().sorted().toList(),
                binding.status(),
                binding.defaultUsage(),
                binding.version());
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
}
