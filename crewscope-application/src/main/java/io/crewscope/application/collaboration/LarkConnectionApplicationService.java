package io.crewscope.application.collaboration;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.credential.CredentialAccessContext;
import io.crewscope.application.credential.CredentialCreateRequest;
import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialMutationContext;
import io.crewscope.application.credential.CredentialReference;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingQuery;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBootstrapLock;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.collaboration.LarkCollaborationCapabilities;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Team-administrator boundary for the complete Lark Connection/Credential/Grant/Binding graph. */
public final class LarkConnectionApplicationService {

    public static final String CREDENTIAL_TYPE = "LARK_APP_CREDENTIAL";
    private static final String CREATE = "lark.create-connection";
    private static final String ROTATE = "lark.rotate-connection-secret";
    private static final String REVOKE = "lark.revoke-connection";

    private final LarkMappingAdministration administration;
    private final ConnectionRepository connections;
    private final ConnectionGrantRepository grants;
    private final CredentialStore credentials;
    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final ProviderDefinitionRepository definitions;
    private final ProviderImplementationRepository implementations;
    private final ProviderBindingRepository bindings;
    private final ProviderBootstrapLock bootstrapLock;
    private final BuiltInProviderRegistration registration;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public LarkConnectionApplicationService(
            LarkMappingAdministration administration,
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            TeamRepository teams,
            WorkspaceRepository workspaces,
            ProviderDefinitionRepository definitions,
            ProviderImplementationRepository implementations,
            ProviderBindingRepository bindings,
            ProviderBootstrapLock bootstrapLock,
            BuiltInProviderRegistration registration,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            ObjectMapper objectMapper) {
        this.administration = Objects.requireNonNull(administration, "administration");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.implementations = Objects.requireNonNull(implementations, "implementations");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.bootstrapLock = Objects.requireNonNull(bootstrapLock, "bootstrapLock");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (registration.type() != ProviderType.COLLABORATION
                || !LarkCollaborationCapabilities.CONNECTOR_KEY.equals(registration.implementationKey())
                || !registration.capabilities().equals(LarkCollaborationCapabilities.COMPLETE)) {
            throw new IllegalArgumentException("registration is incompatible with Lark");
        }
    }

    public CommandExecution<LarkConnectionView> create(
            TeamCommandContext context,
            OrganizationId organizationId,
            CreateLarkConnectionRequest request,
            long expectedVersion) {
        TeamCommandContext trusted = authorize(context, organizationId, request.teamId());
        if (expectedVersion != 0) {
            throw new DomainValidationException("larkConnection.version", "must be zero when creating");
        }
        String secretJson = secretJson(request.appId(), request.appSecret());
        CommandRequestHash hash = CommandRequestHash.sha256(
                CREATE, organizationId.toString(), request.teamId().toString(), request.tenantKey(),
                request.appId(), request.expiresAt().map(Object::toString).orElse(""),
                fingerprint(secretJson));
        try (CredentialSecret secret = CredentialSecret.utf8(secretJson)) {
            return execute(trusted, organizationId, CREATE, hash, commandId -> {
                UtcTimestamp now = timeProvider.now();
                Team team = requireTeam(organizationId, request.teamId());
                Workspace workspace = workspaces.findById(organizationId, team.defaultWorkspaceId())
                        .orElseThrow(() -> new AggregateNotFoundException(
                                "Workspace", team.defaultWorkspaceId()));
                ProviderOwner owner = ProviderOwner.team(team);
                ensureNoActiveConnection(owner);
                Foundation foundation = foundation(organizationId, trusted.access().actor(), now);
                ConnectionId connectionId = ConnectionId.generate();
                CredentialId credentialId = CredentialId.generate();
                CredentialDescriptor credential = credentials.create(new CredentialCreateRequest(
                        credentialId,
                        CredentialSubject.team(organizationId, team.id()),
                        "lark-connection:" + connectionId,
                        LarkCollaborationCapabilities.CONNECTOR_KEY,
                        Optional.of(connectionId.value()),
                        CREDENTIAL_TYPE,
                        Map.of("appIdSuffix", suffix(request.appId())),
                        request.expiresAt(),
                        trusted.access().actor().id()), secret);
                Connection connection = connections.create(Connection.authorize(
                        connectionId, owner, LarkCollaborationCapabilities.CONNECTOR_KEY,
                        request.tenantKey(), credentialId, request.expiresAt(),
                        trusted.access().actor(), now));
                ProviderAccessScope access = new ProviderAccessScope(
                        LarkCollaborationCapabilities.COMPLETE, ProviderResourceScope.allResources());
                ConnectionGrant grant = grants.create(ConnectionGrant.grant(
                        ConnectionGrantId.generate(), connection, owner, access, now,
                        request.expiresAt(), trusted.access().actor(), now));
                ProviderBinding binding = bindings.create(ProviderBinding.bind(
                        ProviderBindingId.generate(), ProviderBindingTarget.workspace(workspace), owner,
                        foundation.definition(), foundation.implementation(), Optional.of(connection),
                        Optional.of(grant), access, true, trusted.access().actor(), now));
                return new Result(view(connection, Optional.of(binding), credential), connection.id().value(), credential.version());
            });
        }
    }

    public List<LarkConnectionView> list(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        authorize(context, organizationId, teamId);
        Team team = requireTeam(organizationId, teamId);
        ProviderOwner owner = ProviderOwner.team(team);
        return connections.findByOwner(owner).stream()
                .filter(value -> LarkCollaborationCapabilities.CONNECTOR_KEY.equals(value.connectorKey()))
                .sorted(Comparator.comparing(
                        (Connection value) -> value.audit().createdAt()).reversed())
                .map(value -> view(value, findBinding(team, owner, value), requireCredential(value, context)))
                .toList();
    }

    public LarkConnectionView get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ConnectionId connectionId) {
        authorize(context, organizationId, teamId);
        Team team = requireTeam(organizationId, teamId);
        ProviderOwner owner = ProviderOwner.team(team);
        Connection connection = requireConnection(organizationId, owner, connectionId);
        return view(connection, findBinding(team, owner, connection), requireCredential(connection, context));
    }

    public CommandExecution<LarkConnectionView> rotate(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ConnectionId connectionId,
            String appId,
            String appSecret,
            long expectedVersion) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        String secretJson = secretJson(appId, appSecret);
        CommandRequestHash hash = CommandRequestHash.sha256(
                ROTATE, organizationId.toString(), teamId.toString(), connectionId.toString(),
                Long.toString(expectedVersion), appId, fingerprint(secretJson));
        try (CredentialSecret secret = CredentialSecret.utf8(secretJson)) {
            return execute(trusted, organizationId, ROTATE, hash, ignored -> {
                Team team = requireTeam(organizationId, teamId);
                ProviderOwner owner = ProviderOwner.team(team);
                Connection connection = requireConnection(organizationId, owner, connectionId);
                CredentialDescriptor current = requireCredential(connection, trusted.access());
                if (!suffix(appId).equals(current.metadata().get("appIdSuffix"))) {
                    throw new DomainValidationException(
                            "larkConnection.appId", "must identify the existing Lark application");
                }
                CredentialDescriptor rotated = credentials.rotate(
                        current.reference(), expectedVersion,
                        new CredentialMutationContext(organizationId, trusted.access().actor().id()), secret);
                ProviderBinding binding = requireBinding(team, owner, connection);
                return new Result(view(connection, Optional.of(binding), rotated), connection.id().value(), rotated.version());
            });
        }
    }

    public CommandExecution<LarkConnectionView> revoke(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ConnectionId connectionId,
            String reason,
            long expectedVersion) {
        TeamCommandContext trusted = authorize(context, organizationId, teamId);
        String normalizedReason = requireReason(reason);
        CommandRequestHash hash = CommandRequestHash.sha256(
                REVOKE, organizationId.toString(), teamId.toString(), connectionId.toString(),
                Long.toString(expectedVersion), normalizedReason);
        return execute(trusted, organizationId, REVOKE, hash, ignored -> {
            UtcTimestamp now = timeProvider.now();
            Team team = requireTeam(organizationId, teamId);
            ProviderOwner owner = ProviderOwner.team(team);
            Connection current = requireConnection(organizationId, owner, connectionId);
            CredentialDescriptor credential = requireCredential(current, trusted.access());
            if (credential.version() != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "Credential", credential.credentialId(), expectedVersion,
                        credential.version());
            }
            ConnectionGrant grant = currentGrant(current, owner);
            grants.update(grant.revoke(
                    grant.version(), trusted.access().actor(), normalizedReason, now));
            ProviderBinding binding = requireBinding(team, owner, current);
            ProviderBinding disabled = binding.status() == ProviderRegistrationStatus.ACTIVE
                    ? bindings.update(binding.disable(binding.version(), trusted.access().actor(), now))
                    : binding;
            CredentialDescriptor revoked = credentials.revoke(
                    credential.reference(), expectedVersion,
                    new CredentialMutationContext(organizationId, trusted.access().actor().id()),
                    CredentialRevocationReason.CONNECTION_REVOKED);
            Connection connection = connections.update(current.revoke(
                    current.version(), trusted.access().actor(), normalizedReason, now));
            return new Result(view(connection, Optional.of(disabled), revoked), connection.id().value(), revoked.version());
        });
    }

    private TeamCommandContext authorize(
            TeamCommandContext context, OrganizationId organizationId, TeamId teamId) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        authorize(trusted.access(), organizationId, teamId);
        return trusted;
    }

    private void authorize(TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        TeamAccessContext access = Objects.requireNonNull(context, "context");
        administration.requireProviderAdministrator(
                organizationId, teamId, access.actor(), timeProvider.now());
    }

    private CommandExecution<LarkConnectionView> execute(
            TeamCommandContext context,
            OrganizationId organizationId,
            String commandType,
            CommandRequestHash hash,
            java.util.function.Function<UUID, Result> action) {
        Optional<CommandReceipt> completed = receipts.findCompleted(
                organizationId, context.idempotencyKey(), commandType, hash);
        if (completed.isPresent()) {
            return CommandExecution.replayed(completed.orElseThrow());
        }
        return transactions.required(() -> {
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId, context.idempotencyKey(), commandType, hash, commandId,
                    context.correlationId(), timeProvider.now()));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            Result result = action.apply(commandId);
            CommandReceipt receipt = new CommandReceipt(
                    commandId, result.factId(), result.version(), context.correlationId());
            receipts.complete(organizationId, context.idempotencyKey(), receipt, timeProvider.now());
            return CommandExecution.completed(result.view(), receipt);
        });
    }

    private Foundation foundation(
            OrganizationId organizationId,
            io.crewscope.domain.identity.Principal actor,
            UtcTimestamp now) {
        bootstrapLock.acquire(organizationId);
        ProviderDefinition candidate = ProviderDefinition.create(
                registration.definitionId(organizationId), organizationId,
                registration.definitionKey(), registration.type(), registration.interfaceVersion(),
                registration.displayName(), registration.capabilities(), actor, now);
        ProviderDefinition definition = definitions.findByKey(organizationId, registration.definitionKey())
                .orElseGet(() -> definitions.create(candidate));
        if (!definition.isActive() || definition.type() != ProviderType.COLLABORATION
                || !definition.capabilities().equals(LarkCollaborationCapabilities.COMPLETE)) {
            throw new DomainValidationException("larkProvider.definition", "conflicts with built-in contract");
        }
        List<ProviderImplementation> matching = implementations
                .findByDefinition(organizationId, definition.id()).stream()
                .filter(value -> registration.implementationKey().equals(value.key()))
                .toList();
        if (matching.size() > 1) {
            throw new DomainValidationException("larkProvider.implementation", "is ambiguous");
        }
        ProviderImplementation implementation = matching.isEmpty()
                ? implementations.create(ProviderImplementation.create(
                        registration.implementationId(organizationId), definition,
                        registration.implementationKey(), registration.implementationVersion(),
                        registration.capabilities(), ProviderConnectionRequirement.REQUIRED,
                        Optional.of(LarkCollaborationCapabilities.CONNECTOR_KEY), actor, now))
                : matching.get(0);
        if (!implementation.supports(definition, LarkCollaborationCapabilities.COMPLETE)
                || implementation.connectionRequirement() != ProviderConnectionRequirement.REQUIRED
                || implementation.connectorKey()
                        .filter(LarkCollaborationCapabilities.CONNECTOR_KEY::equals).isEmpty()) {
            throw new DomainValidationException("larkProvider.implementation", "conflicts with built-in contract");
        }
        return new Foundation(definition, implementation);
    }

    private ProviderBinding requireBinding(Team team, ProviderOwner owner, Connection connection) {
        return findBinding(team, owner, connection).orElseThrow(() ->
                new DomainValidationException(
                        "larkConnection.binding", "must resolve exactly one active Binding"));
    }

    private Optional<ProviderBinding> findBinding(
            Team team, ProviderOwner owner, Connection connection) {
        Workspace workspace = workspaces.findById(team.organizationId(), team.defaultWorkspaceId())
                .orElseThrow(() -> new AggregateNotFoundException("Workspace", team.defaultWorkspaceId()));
        List<ProviderBinding> candidates = bindings.findCandidates(new ProviderBindingQuery(
                        team.organizationId(), team.id(), workspace.id(), Optional.empty(), owner,
                        ProviderType.COLLABORATION,
                        Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT))).stream()
                .filter(value -> value.connectionId().filter(connection.id()::equals).isPresent())
                .toList();
        if (candidates.size() > 1) {
            throw new DomainValidationException("larkConnection.binding", "is ambiguous");
        }
        return candidates.stream().findFirst();
    }

    private ConnectionGrant currentGrant(Connection connection, ProviderOwner owner) {
        List<ConnectionGrant> values = grants.findByConnectionAndGrantee(connection.id(), owner).stream()
                .filter(value -> value.status() == ConnectionGrantStatus.ACTIVE)
                .toList();
        if (values.size() != 1) {
            throw new DomainValidationException("larkConnection.grant", "must resolve exactly one active Grant");
        }
        return values.get(0);
    }

    private CredentialDescriptor requireCredential(Connection connection, TeamAccessContext context) {
        return credentials.describe(
                        new CredentialReference(connection.organizationId(), connection.credentialId()),
                        new CredentialAccessContext(
                                connection.organizationId(), context.actor().id(),
                                Set.of(connection.credentialId()), "lark:connection:metadata"))
                .orElseThrow(() -> new DomainValidationException(
                        "larkConnection.credential", "is unavailable"));
    }

    private Connection requireConnection(
            OrganizationId organizationId, ProviderOwner owner, ConnectionId connectionId) {
        return connections.findById(organizationId, connectionId)
                .filter(value -> value.owner().equals(owner))
                .filter(value -> LarkCollaborationCapabilities.CONNECTOR_KEY.equals(value.connectorKey()))
                .orElseThrow(() -> new AggregateNotFoundException("Connection", connectionId));
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        return teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    }

    private void ensureNoActiveConnection(ProviderOwner owner) {
        boolean exists = connections.findByOwner(owner).stream()
                .anyMatch(value -> LarkCollaborationCapabilities.CONNECTOR_KEY.equals(value.connectorKey())
                        && !value.status().isTerminal());
        if (exists) {
            throw new DomainValidationException(
                    "larkConnection", "Team already has an active Lark Connection");
        }
    }

    private LarkConnectionView view(
            Connection connection,
            Optional<ProviderBinding> binding,
            CredentialDescriptor credential) {
        return new LarkConnectionView(
                connection.id(), connection.owner().teamId().orElseThrow(),
                binding.map(ProviderBinding::id),
                "****" + credential.metadata().getOrDefault("appIdSuffix", ""),
                connection.status(), credential.status(), connection.expiresAt(),
                connection.audit().createdAt(), connection.audit().updatedAt(), credential.version());
    }

    private String secretJson(String appId, String appSecret) {
        try {
            return objectMapper.writeValueAsString(new LarkCredentialPayload(
                    Objects.requireNonNull(appId, "appId").strip(),
                    Objects.requireNonNull(appSecret, "appSecret").strip()));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Lark credential could not be encoded", failure);
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String suffix(String value) {
        String normalized = Objects.requireNonNull(value, "appId").strip();
        return normalized.substring(Math.max(0, normalized.length() - 4));
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 500) {
            throw new DomainValidationException("larkConnection.revokeReason", "is invalid");
        }
        return value.strip();
    }

    private record Foundation(ProviderDefinition definition, ProviderImplementation implementation) {}

    private record Result(LarkConnectionView view, UUID factId, long version) {}

    /** Stable property order keeps the encrypted payload and idempotency fingerprint canonical. */
    private record LarkCredentialPayload(String app_id, String app_secret) {}
}
