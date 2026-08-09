package io.crewscope.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderBindingResolverTest {

    private static final UtcTimestamp T0 = UtcTimestamp.parse("2026-08-09T00:00:00Z");
    private static final UtcTimestamp T1 = UtcTimestamp.parse("2026-08-09T00:01:00Z");

    @Test
    void actionAndTaskExplicitBindingsHaveStrictPrecedenceWithoutFallback() {
        Fixture fixture = Fixture.create();
        ProviderBinding workspace = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), false);
        ProviderBinding task = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), false);
        ProviderBinding action = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), false);
        fixture.store(workspace, task, action);

        ProviderBindingResolution actionResult = fixture.resolver.resolve(
                fixture.request(Optional.of(action.id()), Optional.of(task.id())));
        ProviderBindingResolution taskResult = fixture.resolver.resolve(
                fixture.request(Optional.empty(), Optional.of(task.id())));
        ProviderBindingResolution missingAction = fixture.resolver.resolve(fixture.request(
                Optional.of(ProviderBindingId.generate()), Optional.of(task.id())));

        assertEquals(ProviderBindingResolutionLevel.ACTION_EXPLICIT, actionResult.level());
        assertEquals(action.id(), actionResult.candidate().orElseThrow().binding().id());
        assertEquals(ProviderBindingResolutionLevel.TASK_EXPLICIT, taskResult.level());
        assertEquals(task.id(), taskResult.candidate().orElseThrow().binding().id());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, missingAction.status());
        assertEquals(ProviderBindingResolutionLevel.ACTION_EXPLICIT, missingAction.level());
    }

    @Test
    void workProjectOccupiesTheHigherLevelEvenWhenItsGrantIsRevoked() {
        Fixture fixture = Fixture.create();
        ProviderBinding workspace = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), true);
        ProviderBinding project = fixture.binding(
                ProviderBindingTarget.workProject(fixture.project), true);
        fixture.store(workspace, project);
        fixture.grants.put(
                project.connectionGrantId().orElseThrow(),
                fixture.grant.revoke(0, fixture.actor, "revoked", T1));

        ProviderBindingResolution result = fixture.resolver.resolve(fixture.request());

        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, result.status());
        assertEquals(ProviderBindingResolutionLevel.WORK_PROJECT, result.level());
    }

    @Test
    void uniqueDefaultWinsAndAnUnusableDefaultDoesNotFallThroughToNonDefault() {
        Fixture fixture = Fixture.create();
        ProviderBinding nonDefault = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), false);
        ProviderBinding selectedDefault = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), true);
        fixture.store(nonDefault, selectedDefault);

        ProviderBindingResolution selected = fixture.resolver.resolve(fixture.request());
        fixture.connections.put(
                selectedDefault.connectionId().orElseThrow(),
                fixture.connection.suspend(0, fixture.actor, T1));
        ProviderBindingResolution closed = fixture.resolver.resolve(fixture.request());

        assertEquals(
                selectedDefault.id(), selected.candidate().orElseThrow().binding().id());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, closed.status());
        assertEquals(ProviderBindingResolutionLevel.WORKSPACE, closed.level());
    }

    @Test
    void multipleCurrentNonDefaultsAreReportedAsStableAmbiguity() {
        Fixture fixture = Fixture.create();
        ProviderBinding first = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), false);
        ProviderBinding second = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), false);
        fixture.store(second, first, second);

        ProviderBindingResolution result = fixture.resolver.resolve(fixture.request());

        assertEquals(ProviderBindingResolutionStatus.AMBIGUOUS, result.status());
        assertEquals(ProviderBindingResolutionLevel.WORKSPACE, result.level());
        assertEquals(2, result.ambiguousBindingIds().size());
        assertTrue(result.candidate().isEmpty());
    }

    @Test
    void ownerExecutionIdentityAndRequestedAccessRemainExact() {
        Fixture fixture = Fixture.create();
        ProviderBinding binding = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), true);
        fixture.store(binding);
        ProviderAccessScope requested = access(
                ProviderCapabilities.of("source.read"), "repository:crewscope");

        ProviderBindingResolution resolved = fixture.resolver.resolve(fixture.request(requested));
        assertEquals(
                Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT),
                fixture.lastQuery.executionIdentity());
        ProviderBindingResolution wrongIdentity = fixture.resolver.resolve(fixture.request(
                Optional.of(ProviderExecutionIdentity.DELEGATED_USER), requested));
        ProviderBindingResolution connectionless = fixture.resolver.resolve(
                fixture.request(Optional.empty(), requested));
        ProviderBindingResolution wrongOwner = fixture.resolver.resolve(fixture.request(
                ProviderOwner.organization(fixture.organizationId),
                Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT),
                requested));

        assertEquals(requested, resolved.candidate().orElseThrow().effectiveAccess());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, wrongIdentity.status());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, connectionless.status());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, wrongOwner.status());
    }

    @Test
    void currentRegistryVersionsAndRequestedEnvelopeFailClosed() {
        Fixture fixture = Fixture.create();
        ProviderBinding binding = fixture.binding(
                ProviderBindingTarget.workspace(fixture.team.defaultWorkspace()), true);
        fixture.store(binding);

        fixture.definitions.put(
                fixture.definition.id(), fixture.definition.disable(0, fixture.actor, T1));
        ProviderBindingResolution staleDefinition = fixture.resolver.resolve(fixture.request());
        fixture.definitions.put(fixture.definition.id(), fixture.definition);
        fixture.implementations.put(
                fixture.implementation.id(),
                fixture.implementation.disable(0, fixture.actor, T1));
        ProviderBindingResolution staleImplementation = fixture.resolver.resolve(fixture.request());
        fixture.implementations.put(fixture.implementation.id(), fixture.implementation);
        ProviderBindingResolution noCapability = fixture.resolver.resolve(fixture.request(access(
                ProviderCapabilities.of("issues.delete"), "repository:crewscope")));

        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, staleDefinition.status());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, staleImplementation.status());
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, noCapability.status());
    }

    private static ProviderAccessScope access(
            ProviderCapabilities capabilities, String... resources) {
        return new ProviderAccessScope(capabilities, ProviderResourceScope.of(resources));
    }

    private static final class Fixture
            implements ProviderBindingRepository,
                    ProviderDefinitionRepository,
                    ProviderImplementationRepository,
                    ConnectionRepository,
                    ConnectionGrantRepository {

        private final OrganizationId organizationId;
        private final Principal actor;
        private final TeamInitialization team;
        private final WorkProject project;
        private final ProviderOwner owner;
        private final ProviderDefinition definition;
        private final ProviderImplementation implementation;
        private final Connection connection;
        private final ConnectionGrant grant;
        private final Map<ProviderBindingId, ProviderBinding> bindings = new LinkedHashMap<>();
        private final Map<ProviderDefinitionId, ProviderDefinition> definitions =
                new LinkedHashMap<>();
        private final Map<ProviderImplementationId, ProviderImplementation> implementations =
                new LinkedHashMap<>();
        private final Map<ConnectionId, Connection> connections = new LinkedHashMap<>();
        private final Map<ConnectionGrantId, ConnectionGrant> grants = new LinkedHashMap<>();
        private final ProviderBindingResolver resolver;
        private ProviderBindingQuery lastQuery;

        private Fixture(
                OrganizationId organizationId,
                Principal actor,
                TeamInitialization team,
                WorkProject project,
                ProviderOwner owner,
                ProviderDefinition definition,
                ProviderImplementation implementation,
                Connection connection,
                ConnectionGrant grant) {
            this.organizationId = organizationId;
            this.actor = actor;
            this.team = team;
            this.project = project;
            this.owner = owner;
            this.definition = definition;
            this.implementation = implementation;
            this.connection = connection;
            this.grant = grant;
            definitions.put(definition.id(), definition);
            implementations.put(implementation.id(), implementation);
            connections.put(connection.id(), connection);
            grants.put(grant.id(), grant);
            resolver = new ProviderBindingResolver(this, this, this, this, this, () -> T1);
        }

        static Fixture create() {
            OrganizationId organizationId = OrganizationId.generate();
            Principal actor = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    T0);
            TeamInitialization team = TeamInitialization.create(actor, "Platform", T0);
            WorkProject project = WorkProject.create(
                    WorkProjectId.generate(),
                    new WorkProjectKey("PRV"),
                    "Provider",
                    team.team(),
                    team.defaultWorkspace(),
                    actor,
                    T0);
            ProviderDefinition definition = ProviderDefinition.create(
                    ProviderDefinitionId.generate(),
                    organizationId,
                    "source-code",
                    ProviderType.SOURCE_CODE,
                    "1.0.0",
                    "Source Code",
                    ProviderCapabilities.of("source.read", "source.write"),
                    actor,
                    T0);
            ProviderImplementation implementation = ProviderImplementation.create(
                    ProviderImplementationId.generate(),
                    definition,
                    "github-source-code",
                    "1.0.0",
                    definition.capabilities(),
                    ProviderConnectionRequirement.REQUIRED,
                    Optional.of("github"),
                    actor,
                    T0);
            ProviderOwner owner = ProviderOwner.team(team.team());
            Connection connection = Connection.authorize(
                    ConnectionId.generate(),
                    owner,
                    "github",
                    "github-account",
                    CredentialId.generate(),
                    Optional.empty(),
                    actor,
                    T0);
            ConnectionGrant grant = ConnectionGrant.grant(
                    ConnectionGrantId.generate(),
                    connection,
                    owner,
                    access(
                            ProviderCapabilities.of("source.read", "source.write"),
                            "repository:crewscope",
                            "repository:other"),
                    T0,
                    Optional.empty(),
                    actor,
                    T0);
            return new Fixture(
                    organizationId,
                    actor,
                    team,
                    project,
                    owner,
                    definition,
                    implementation,
                    connection,
                    grant);
        }

        ProviderBinding binding(ProviderBindingTarget target, boolean defaultUsage) {
            return ProviderBinding.bind(
                    ProviderBindingId.generate(),
                    target,
                    owner,
                    definition,
                    implementation,
                    Optional.of(connection),
                    Optional.of(grant),
                    access(
                            ProviderCapabilities.of("source.read", "source.write"),
                            "repository:crewscope",
                            "repository:other"),
                    defaultUsage,
                    actor,
                    T0);
        }

        void store(ProviderBinding... values) {
            for (ProviderBinding value : values) {
                bindings.putIfAbsent(value.id(), value);
            }
        }

        ProviderBindingResolutionRequest request() {
            return request(Optional.empty(), Optional.empty());
        }

        ProviderBindingResolutionRequest request(
                Optional<ProviderBindingId> actionBindingId,
                Optional<ProviderBindingId> taskBindingId) {
            return new ProviderBindingResolutionRequest(
                    organizationId,
                    team.team().id(),
                    team.defaultWorkspace().id(),
                    Optional.of(project.id()),
                    owner,
                    ProviderType.SOURCE_CODE,
                    Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT),
                    access(ProviderCapabilities.of("source.read"), "repository:crewscope"),
                    actionBindingId,
                    taskBindingId);
        }

        ProviderBindingResolutionRequest request(ProviderAccessScope requestedAccess) {
            return request(
                    Optional.of(ProviderExecutionIdentity.TEAM_SERVICE_ACCOUNT), requestedAccess);
        }

        ProviderBindingResolutionRequest request(
                Optional<ProviderExecutionIdentity> identity,
                ProviderAccessScope requestedAccess) {
            return request(owner, identity, requestedAccess);
        }

        ProviderBindingResolutionRequest request(
                ProviderOwner requestedOwner,
                Optional<ProviderExecutionIdentity> identity,
                ProviderAccessScope requestedAccess) {
            return new ProviderBindingResolutionRequest(
                    organizationId,
                    team.team().id(),
                    team.defaultWorkspace().id(),
                    Optional.of(project.id()),
                    requestedOwner,
                    ProviderType.SOURCE_CODE,
                    identity,
                    requestedAccess,
                    Optional.empty(),
                    Optional.empty());
        }

        @Override
        public ProviderBinding create(ProviderBinding value) {
            store(value);
            return value;
        }

        @Override
        public ProviderBinding update(ProviderBinding value) {
            bindings.put(value.id(), value);
            return value;
        }

        @Override
        public Optional<ProviderBinding> findById(
                OrganizationId requestedOrganizationId, ProviderBindingId id) {
            return Optional.ofNullable(bindings.get(id))
                    .filter(value -> value.organizationId().equals(requestedOrganizationId));
        }

        @Override
        public List<ProviderBinding> findCandidates(ProviderBindingQuery query) {
            lastQuery = query;
            return new ArrayList<>(bindings.values());
        }

        @Override
        public ProviderDefinition create(ProviderDefinition value) {
            definitions.put(value.id(), value);
            return value;
        }

        @Override
        public ProviderDefinition update(ProviderDefinition value) {
            definitions.put(value.id(), value);
            return value;
        }

        @Override
        public Optional<ProviderDefinition> findById(
                OrganizationId requestedOrganizationId, ProviderDefinitionId id) {
            return Optional.ofNullable(definitions.get(id))
                    .filter(value -> value.organizationId().equals(requestedOrganizationId));
        }

        @Override
        public Optional<ProviderDefinition> findByKey(
                OrganizationId requestedOrganizationId, String key) {
            return definitions.values().stream()
                    .filter(value -> value.organizationId().equals(requestedOrganizationId))
                    .filter(value -> value.key().equals(key))
                    .findFirst();
        }

        @Override
        public ProviderImplementation create(ProviderImplementation value) {
            implementations.put(value.id(), value);
            return value;
        }

        @Override
        public ProviderImplementation update(ProviderImplementation value) {
            implementations.put(value.id(), value);
            return value;
        }

        @Override
        public Optional<ProviderImplementation> findById(
                OrganizationId requestedOrganizationId, ProviderImplementationId id) {
            return Optional.ofNullable(implementations.get(id))
                    .filter(value -> value.organizationId().equals(requestedOrganizationId));
        }

        @Override
        public List<ProviderImplementation> findByDefinition(
                OrganizationId requestedOrganizationId, ProviderDefinitionId definitionId) {
            return implementations.values().stream()
                    .filter(value -> value.organizationId().equals(requestedOrganizationId))
                    .filter(value -> value.definitionId().equals(definitionId))
                    .toList();
        }

        @Override
        public Connection create(Connection value) {
            connections.put(value.id(), value);
            return value;
        }

        @Override
        public Connection update(Connection value) {
            connections.put(value.id(), value);
            return value;
        }

        @Override
        public Optional<Connection> findById(
                OrganizationId requestedOrganizationId, ConnectionId id) {
            return Optional.ofNullable(connections.get(id))
                    .filter(value -> value.organizationId().equals(requestedOrganizationId));
        }

        @Override
        public List<Connection> findByOwner(ProviderOwner requestedOwner) {
            return connections.values().stream()
                    .filter(value -> value.owner().equals(requestedOwner))
                    .toList();
        }

        @Override
        public ConnectionGrant create(ConnectionGrant value) {
            grants.put(value.id(), value);
            return value;
        }

        @Override
        public ConnectionGrant update(ConnectionGrant value) {
            grants.put(value.id(), value);
            return value;
        }

        @Override
        public Optional<ConnectionGrant> findById(
                OrganizationId requestedOrganizationId, ConnectionGrantId id) {
            return Optional.ofNullable(grants.get(id))
                    .filter(value -> value.organizationId().equals(requestedOrganizationId));
        }

        @Override
        public List<ConnectionGrant> findByConnectionAndGrantee(
                ConnectionId connectionId, ProviderOwner grantee) {
            return grants.values().stream()
                    .filter(value -> value.connectionId().equals(connectionId))
                    .filter(value -> value.grantee().equals(grantee))
                    .toList();
        }
    }
}
