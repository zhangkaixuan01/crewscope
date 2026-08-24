package io.crewscope.application.model;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.credential.CredentialRevocationReason;
import io.crewscope.application.credential.CredentialSecret;
import io.crewscope.application.credential.CredentialSubject;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelConnectionRevocationReason;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Application boundary for safe model catalog reads and owner-scoped connection management. */
public final class ModelConnectionApplicationService {

    private static final String CREATE = "CREATE_MODEL_CONNECTION";
    private static final String VERIFY = "VERIFY_MODEL_CONNECTION";
    private static final String ROTATE = "ROTATE_MODEL_CONNECTION_CREDENTIAL";
    private static final String SUSPEND = "SUSPEND_MODEL_CONNECTION";
    private static final String REVOKE = "REVOKE_MODEL_CONNECTION";

    private final ModelProviderDefinitionRepository providers;
    private final ModelCatalogEntryRepository catalogs;
    private final ModelPriceScheduleRepository prices;
    private final ModelConnectionRepository connections;
    private final ModelConnectionCredentialService credentials;
    private final CommandReceiptStore receiptStore;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final TimeProvider timeProvider;

    public ModelConnectionApplicationService(
            ModelProviderDefinitionRepository providers,
            ModelCatalogEntryRepository catalogs,
            ModelPriceScheduleRepository prices,
            ModelConnectionRepository connections,
            ModelConnectionCredentialService credentials,
            CommandReceiptStore receiptStore,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TimeProvider timeProvider) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.receiptStore = Objects.requireNonNull(receiptStore, "receiptStore");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns trusted provider metadata without endpoints or adapter implementation details. */
    public List<ModelProviderDefinition> listProviders(
            TeamAccessContext context, OrganizationId organizationId, int offset, int limit) {
        requireOrganizationUser(context, organizationId);
        return providers.findPage(offset, limit);
    }

    /** Returns versioned catalog and effective prices without connection or credential data. */
    public List<ModelCatalogItemView> listCatalog(
            TeamAccessContext context,
            OrganizationId organizationId,
            ModelProviderKey providerKey,
            int offset,
            int limit) {
        requireOrganizationUser(context, organizationId);
        requireProvider(providerKey);
        UtcTimestamp now = timeProvider.now();
        return catalogs.findPage(providerKey, offset, limit).stream()
                .map(catalog -> new ModelCatalogItemView(
                        catalog, prices.findEffectivePrice(catalog.coordinate(), now)))
                .toList();
    }

    /** Creates a server-shaped owner, billing subject and CredentialStore binding. */
    public CommandExecution<ModelConnection> create(
            TeamCommandContext context,
            CreateModelConnectionRequest request,
            CredentialSecret secret) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CreateModelConnectionRequest required = Objects.requireNonNull(request, "request");
        try (CredentialSecret plaintext = Objects.requireNonNull(secret, "secret")) {
            Principal actor = requireOrganizationUser(
                    trusted.access(), trusted.access().actor().scope().organizationId());
            ModelConnectionOwner owner = resolveOwner(
                    trusted.access(), required.ownerType(), required.teamId(), true, timeProvider.now());
            ModelProviderDefinition provider = requireProvider(required.providerKey());
            ModelConnectionId connectionId = ModelConnectionId.generate();
            CredentialId credentialId = CredentialId.generate();
            SubjectFacts subjects = subjects(owner, actor);
            CommandRequestHash requestHash = CommandRequestHash.sha256(
                    CREATE,
                    actor.id().toString(),
                    owner.type().name(),
                    owner.ownerId().toString(),
                    provider.providerKey().toString(),
                    required.region().toString(),
                    required.credentialExpiresAt().map(Object::toString).orElse(""),
                    secretFingerprint(plaintext));
            CreateModelConnectionCredentialCommand command = new CreateModelConnectionCredentialCommand(
                    connectionId,
                    provider.providerKey(),
                    owner,
                    provider.defaultEndpoint(),
                    required.region(),
                    subjects.billing(),
                    credentialId,
                    subjects.credential(),
                    "model-connection:" + connectionId,
                    Map.of("ownerType", owner.type().name()),
                    required.credentialExpiresAt(),
                    actor.id(),
                    trusted.correlationId());
            return credentials.create(
                    command,
                    plaintext,
                    gate(trusted, CREATE, requestHash, () -> requireManageOwner(
                            trusted.access(), owner, timeProvider.now())));
        }
    }

    public List<ModelConnection> listConnections(
            TeamAccessContext context,
            OrganizationId organizationId,
            ModelConnectionOwnerType ownerType,
            Optional<TeamId> teamId,
            int offset,
            int limit) {
        requireOrganizationUser(context, organizationId);
        ModelConnectionOwner owner = resolveOwner(context, ownerType, teamId, false, timeProvider.now());
        return connections.findByOwner(owner, offset, limit);
    }

    public ModelConnection getConnection(
            TeamAccessContext context,
            OrganizationId organizationId,
            ModelConnectionId connectionId) {
        requireOrganizationUser(context, organizationId);
        ModelConnection connection = requireConnection(organizationId, connectionId);
        requireViewOwner(context, connection.owner(), timeProvider.now());
        return connection;
    }

    public CommandExecution<ModelConnection> verify(
            TeamCommandContext context,
            ModelConnectionId connectionId,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion) {
        return mutate(context, connectionId, expectedVersion, expectedCredentialVersion, VERIFY, "", null);
    }

    public CommandExecution<ModelConnection> rotate(
            TeamCommandContext context,
            ModelConnectionId connectionId,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion,
            CredentialSecret replacement) {
        try (CredentialSecret secret = Objects.requireNonNull(replacement, "replacement")) {
            return mutate(
                    context,
                    connectionId,
                    expectedVersion,
                    expectedCredentialVersion,
                    ROTATE,
                    secretFingerprint(secret),
                    secret);
        }
    }

    public CommandExecution<ModelConnection> suspend(
            TeamCommandContext context,
            ModelConnectionId connectionId,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion) {
        return mutate(context, connectionId, expectedVersion, expectedCredentialVersion, SUSPEND, "", null);
    }

    public CommandExecution<ModelConnection> revoke(
            TeamCommandContext context,
            ModelConnectionId connectionId,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion,
            ModelConnectionRevocationReason reason) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ModelConnection current = requireManageConnection(trusted.access(), connectionId);
        ModelConnectionRevocationReason requiredReason = Objects.requireNonNull(reason, "reason");
        CommandRequestHash hash = mutationHash(
                trusted, REVOKE, current, expectedVersion, expectedCredentialVersion, requiredReason.name());
        RevokeModelConnectionCredentialCommand command = new RevokeModelConnectionCredentialCommand(
                current.organizationId(),
                current.id(),
                expectedVersion,
                expectedCredentialVersion,
                credentialReason(requiredReason),
                requiredReason,
                trusted.access().actor().id(),
                trusted.correlationId());
        return credentials.revoke(command, mutationGate(trusted, REVOKE, hash, current));
    }

    private CommandExecution<ModelConnection> mutate(
            TeamCommandContext context,
            ModelConnectionId connectionId,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion,
            String commandType,
            String extraHash,
            CredentialSecret secret) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ModelConnection current = requireManageConnection(trusted.access(), connectionId);
        CommandRequestHash hash = mutationHash(
                trusted, commandType, current, expectedVersion, expectedCredentialVersion, extraHash);
        ModelConnectionCredentialCommand command = new ModelConnectionCredentialCommand(
                current.organizationId(),
                current.id(),
                expectedVersion,
                expectedCredentialVersion,
                trusted.access().actor().id(),
                trusted.correlationId());
        ModelConnectionLifecycleCommandGate gate = mutationGate(trusted, commandType, hash, current);
        return switch (commandType) {
            case VERIFY -> credentials.verify(command, gate);
            case ROTATE -> credentials.rotate(command, Objects.requireNonNull(secret, "secret"), gate);
            case SUSPEND -> credentials.suspend(command, gate);
            default -> throw new IllegalArgumentException("Unsupported model connection mutation");
        };
    }

    private ModelConnectionLifecycleCommandGate mutationGate(
            TeamCommandContext context,
            String commandType,
            CommandRequestHash requestHash,
            ModelConnection expected) {
        return gate(context, commandType, requestHash, () -> {
            ModelConnection current = requireConnection(expected.organizationId(), expected.id());
            if (!current.owner().equals(expected.owner())) {
                throw new PolicyDeniedException("manage this Model Connection");
            }
            requireManageOwner(context.access(), current.owner(), timeProvider.now());
        });
    }

    private ModelConnectionLifecycleCommandGate gate(
            TeamCommandContext context,
            String commandType,
            CommandRequestHash requestHash,
            Runnable currentAuthority) {
        UUID commandId = UUID.randomUUID();
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        return new ModelConnectionLifecycleCommandGate() {
            @Override
            public Optional<CommandReceipt> findCompletedReplay() {
                currentAuthority.run();
                return receiptStore.findCompleted(
                        organizationId,
                        context.idempotencyKey(),
                        commandType,
                        requestHash);
            }

            @Override
            public CommandReservation reserve(UtcTimestamp occurredAt) {
                currentAuthority.run();
                return receiptStore.reserve(new CommandReservationRequest(
                        organizationId,
                        context.idempotencyKey(),
                        commandType,
                        requestHash,
                        commandId,
                        context.correlationId(),
                        occurredAt));
            }

            @Override
            public CommandReceipt complete(
                    UUID domainEventId, long committedVersion, UtcTimestamp occurredAt) {
                CommandReceipt receipt = new CommandReceipt(
                        commandId, domainEventId, committedVersion, context.correlationId());
                receiptStore.complete(
                        organizationId, context.idempotencyKey(), receipt, occurredAt);
                return receipt;
            }
        };
    }

    private ModelConnection requireManageConnection(
            TeamAccessContext context, ModelConnectionId connectionId) {
        OrganizationId organizationId = requireOrganizationUser(
                context, context.actor().scope().organizationId()).scope().organizationId();
        ModelConnection connection = requireConnection(organizationId, connectionId);
        requireManageOwner(context, connection.owner(), timeProvider.now());
        return connection;
    }

    private ModelConnection requireConnection(
            OrganizationId organizationId, ModelConnectionId connectionId) {
        return connections.findById(organizationId, Objects.requireNonNull(connectionId, "connectionId"))
                .orElseThrow(() -> new AggregateNotFoundException("ModelConnection", connectionId));
    }

    private ModelProviderDefinition requireProvider(ModelProviderKey providerKey) {
        return providers.findByKey(Objects.requireNonNull(providerKey, "providerKey"))
                .orElseThrow(() -> new ModelConnectionCredentialException(
                        ModelConnectionCredentialException.Error.PROVIDER_NOT_FOUND,
                        "Model provider was not found"));
    }

    private ModelConnectionOwner resolveOwner(
            TeamAccessContext context,
            ModelConnectionOwnerType type,
            Optional<TeamId> teamId,
            boolean manage,
            UtcTimestamp now) {
        Principal actor = requireOrganizationUser(context, context.actor().scope().organizationId());
        ModelConnectionOwnerType requiredType = Objects.requireNonNull(type, "type");
        return switch (requiredType) {
            case USER -> {
                if (teamId.isPresent()) {
                    throw new DomainValidationException("modelConnection.owner.teamId", "must be absent for USER ownership");
                }
                yield ModelConnectionOwner.user(actor);
            }
            case TEAM -> {
                TeamId requiredTeamId = teamId.orElseThrow(() -> new DomainValidationException(
                        "modelConnection.owner.teamId", "is required for TEAM ownership"));
                Team team = requireTeam(actor.scope().organizationId(), requiredTeamId);
                if (manage) {
                    requireTeamManage(context, team, now);
                } else {
                    requireActiveMember(actor, team);
                }
                yield ModelConnectionOwner.team(team);
            }
            case ORGANIZATION -> {
                if (teamId.isPresent()) {
                    throw new DomainValidationException("modelConnection.owner.teamId", "must be absent for ORGANIZATION ownership");
                }
                if (!context.platformAdministrator()) {
                    throw new PolicyDeniedException("manage Organization Model Connections");
                }
                yield ModelConnectionOwner.organization(actor.scope().organizationId());
            }
        };
    }

    private void requireViewOwner(
            TeamAccessContext context, ModelConnectionOwner owner, UtcTimestamp now) {
        switch (owner.type()) {
            case USER -> {
                if (!owner.userPrincipalId().orElseThrow().equals(context.actor().id())) {
                    throw new PolicyDeniedException("view this Model Connection");
                }
            }
            case TEAM -> requireActiveMember(
                    context.actor(), requireTeam(owner.organizationId(), owner.teamId().orElseThrow()));
            case ORGANIZATION -> requireOrganizationUser(context, owner.organizationId());
        }
    }

    private void requireManageOwner(
            TeamAccessContext context, ModelConnectionOwner owner, UtcTimestamp now) {
        switch (owner.type()) {
            case USER -> {
                if (!owner.userPrincipalId().orElseThrow().equals(context.actor().id())) {
                    throw new PolicyDeniedException("manage this Model Connection");
                }
            }
            case TEAM -> requireTeamManage(
                    context, requireTeam(owner.organizationId(), owner.teamId().orElseThrow()), now);
            case ORGANIZATION -> {
                requireOrganizationUser(context, owner.organizationId());
                if (!context.platformAdministrator()) {
                    throw new PolicyDeniedException("manage Organization Model Connections");
                }
            }
        }
    }

    private void requireTeamManage(TeamAccessContext context, Team team, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        TeamMember member = requireActiveMember(context.actor(), team);
        Map<TeamRoleId, TeamRole> rolesById = roles.findByTeam(team.organizationId(), team.id()).stream()
                .collect(Collectors.toMap(TeamRole::id, role -> role));
        boolean allowed = grants.findByMember(team.organizationId(), member.id()).stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(now))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> rolesById.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(role -> role.permissions().contains(TeamPermission.PROVIDER_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage Team Model Connections");
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
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's Model Connections"));
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

    private static SubjectFacts subjects(ModelConnectionOwner owner, Principal actor) {
        return switch (owner.type()) {
            case USER -> new SubjectFacts(
                    CredentialSubject.principal(owner.organizationId(), actor.id()),
                    ModelBillingSubject.principal(owner.organizationId(), actor.id()));
            case TEAM -> new SubjectFacts(
                    CredentialSubject.team(owner.organizationId(), owner.teamId().orElseThrow()),
                    ModelBillingSubject.team(owner.organizationId(), owner.teamId().orElseThrow()));
            case ORGANIZATION -> new SubjectFacts(
                    CredentialSubject.organization(owner.organizationId()),
                    ModelBillingSubject.organization(owner.organizationId()));
        };
    }

    private static CommandRequestHash mutationHash(
            TeamCommandContext context,
            String commandType,
            ModelConnection connection,
            long expectedVersion,
            ModelCredentialVersion expectedCredentialVersion,
            String extra) {
        return CommandRequestHash.sha256(
                commandType,
                context.access().actor().id().toString(),
                connection.id().toString(),
                Long.toString(expectedVersion),
                expectedCredentialVersion.toString(),
                extra);
    }

    private static String secretFingerprint(CredentialSecret secret) {
        byte[] bytes = secret.copyBytes();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static CredentialRevocationReason credentialReason(
            ModelConnectionRevocationReason reason) {
        return switch (reason) {
            case OWNER_REQUESTED -> CredentialRevocationReason.USER_REQUESTED;
            case SECURITY_INCIDENT, POLICY_REVOKED -> CredentialRevocationReason.SECURITY_POLICY;
            case CREDENTIAL_REVOKED, PROVIDER_DISABLED -> CredentialRevocationReason.CONNECTION_REVOKED;
        };
    }

    private record SubjectFacts(CredentialSubject credential, ModelBillingSubject billing) {}
}
