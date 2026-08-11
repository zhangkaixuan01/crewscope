package io.crewscope.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workspace.Workspace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Covers built-in Provider bootstrap idempotency and fail-closed Team Binding queries. */
class BuiltInProviderInitializationServiceTest {

  private static final UtcTimestamp T0 = UtcTimestamp.parse("2026-08-11T10:00:00Z");
  private static final UtcTimestamp T1 = UtcTimestamp.parse("2026-08-11T10:01:00Z");
  private static final TransactionExecutor DIRECT_TRANSACTION =
      new TransactionExecutor() {
        @Override
        public <T> T required(Supplier<T> operation) {
          return operation.get();
        }
      };

  @Test
  void initializesOneStableConnectionlessFoundationAndReplaysWithoutWrites() {
    Fixture fixture = new Fixture();

    ProviderFoundation first = fixture.initialize();
    ProviderFoundation replay = fixture.initialize();

    assertEquals(first, replay);
    assertEquals(1, fixture.definitionCreates);
    assertEquals(1, fixture.implementationCreates);
    assertEquals(1, fixture.bindingCreates);
    assertEquals(2, fixture.lockCount);
    assertEquals(ProviderRegistrationStatus.ACTIVE, first.binding().status());
    assertTrue(first.binding().defaultUsage());
    assertTrue(first.binding().connectionId().isEmpty());
    assertEquals(
        fixture.registration.workspaceAccess(fixture.team.defaultWorkspace().id()),
        first.binding().effectiveAccess());
  }

  @Test
  void rejectsARegistryKeyThatConflictsWithTheProductContract() {
    Fixture fixture = new Fixture();
    ProviderDefinition conflicting =
        ProviderDefinition.create(
            ProviderDefinitionId.generate(),
            fixture.organizationId,
            fixture.registration.definitionKey(),
            ProviderType.WORK_ITEM,
            "9.0.0",
            "Conflicting",
            ProviderCapabilities.of("workitem.read"),
            fixture.owner,
            T0);
    fixture.definitions.put(conflicting.id(), conflicting);

    assertThrows(DomainValidationException.class, fixture::initialize);
    assertEquals(0, fixture.implementationCreates);
    assertEquals(0, fixture.bindingCreates);
  }

  @Test
  void resolvesTheDefaultOnlyForAnActiveTeamMember() {
    Fixture fixture = new Fixture();
    ProviderFoundation foundation = fixture.initialize();

    ProviderBindingLookup lookup =
        fixture.query.resolveDefault(
            new TeamAccessContext(fixture.owner, false),
            fixture.organizationId,
            fixture.team.team().id());

    assertEquals(ProviderBindingResolutionStatus.RESOLVED, lookup.resolution().status());
    assertEquals(ProviderBindingResolutionLevel.WORKSPACE, lookup.resolution().level());
    assertEquals(
        foundation.binding().id(),
        lookup.resolution().candidate().orElseThrow().binding().id());

    Principal outsider = activeUser(fixture.organizationId, "Outsider");
    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.query.resolveDefault(
                new TeamAccessContext(outsider, false),
                fixture.organizationId,
                fixture.team.team().id()));
  }

  @Test
  void disabledDefaultStaysDisabledAndReturnsNotFound() {
    Fixture fixture = new Fixture();
    ProviderFoundation foundation = fixture.initialize();
    ProviderBinding disabled = foundation.binding().disable(0, fixture.owner, T1);
    fixture.bindings.put(disabled.id(), disabled);

    ProviderFoundation replay = fixture.initialize();
    ProviderBindingLookup lookup =
        fixture.query.resolveDefault(
            new TeamAccessContext(fixture.owner, false),
            fixture.organizationId,
            fixture.team.team().id());

    assertEquals(ProviderRegistrationStatus.DISABLED, replay.binding().status());
    assertEquals(1, fixture.bindingCreates);
    assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, lookup.resolution().status());
    assertEquals(ProviderBindingResolutionLevel.NONE, lookup.resolution().level());
  }

  @Test
  void reportsStableAmbiguityWhenNoDefaultAndTwoCurrentBindingsExist() {
    Fixture fixture = new Fixture();
    ProviderFoundation foundation = fixture.initialize();
    fixture.bindings.put(
        foundation.binding().id(), foundation.binding().disable(0, fixture.owner, T1));
    ProviderBinding first = fixture.nonDefault(foundation, ProviderBindingId.generate());
    fixture.bindings.put(first.id(), first);
    ProviderBinding second = fixture.nonDefault(foundation, ProviderBindingId.generate());
    fixture.bindings.put(second.id(), second);

    ProviderBindingLookup lookup =
        fixture.query.resolveDefault(
            new TeamAccessContext(fixture.owner, false),
            fixture.organizationId,
            fixture.team.team().id());

    assertEquals(ProviderBindingResolutionStatus.AMBIGUOUS, lookup.resolution().status());
    assertEquals(ProviderBindingResolutionLevel.WORKSPACE, lookup.resolution().level());
    assertEquals(2, lookup.resolution().ambiguousBindingIds().size());
  }

  private static BuiltInProviderRegistration registration() {
    return new BuiltInProviderRegistration(
        "work-item",
        ProviderType.WORK_ITEM,
        "1.0.0",
        "CrewScope WorkItem",
        "native-work-item",
        "1.0.0",
        ProviderCapabilities.of(
            "workitem.read",
            "workitem.create",
            "workitem.update",
            "workitem.comment",
            "workitem.resource-link"));
  }

  private static Principal activeUser(OrganizationId organizationId, String name) {
    return Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        name,
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        T0);
  }

  private static final class Fixture
      implements ProviderDefinitionRepository,
          ProviderImplementationRepository,
          ProviderBindingRepository,
          TeamRepository,
          WorkspaceRepository,
          TeamMembershipQuery {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner = activeUser(organizationId, "Owner");
    private final TeamInitialization team = TeamInitialization.create(owner, "Platform", T0);
    private final BuiltInProviderRegistration registration = registration();
    private final Map<ProviderDefinitionId, ProviderDefinition> definitions =
        new LinkedHashMap<>();
    private final Map<ProviderImplementationId, ProviderImplementation> implementations =
        new LinkedHashMap<>();
    private final Map<ProviderBindingId, ProviderBinding> bindings = new LinkedHashMap<>();
    private int definitionCreates;
    private int implementationCreates;
    private int bindingCreates;
    private int lockCount;
    private final BuiltInProviderInitializationService initializer =
        new BuiltInProviderInitializationService(
            registration,
            this,
            this,
            this,
            ignored -> lockCount++,
            DIRECT_TRANSACTION,
            () -> T0);
    private final ProviderBindingResolver resolver =
        new ProviderBindingResolver(
            this,
            this,
            this,
            new EmptyConnectionRepository(),
            new EmptyConnectionGrantRepository(),
            () -> T1);
    private final ProviderBindingQueryService query =
        new ProviderBindingQueryService(
            registration, this, this, this, resolver, DIRECT_TRANSACTION);

    private ProviderFoundation initialize() {
      return initializer.initialize(team.team(), team.defaultWorkspace(), owner);
    }

    private ProviderBinding nonDefault(
        ProviderFoundation foundation, ProviderBindingId id) {
      return ProviderBinding.bind(
          id,
          ProviderBindingTarget.workspace(team.defaultWorkspace()),
          ProviderOwner.team(team.team()),
          foundation.definition(),
          foundation.implementation(),
          Optional.empty(),
          Optional.empty(),
          registration.workspaceAccess(team.defaultWorkspace().id()),
          false,
          owner,
          T0);
    }

    @Override
    public ProviderDefinition create(ProviderDefinition value) {
      definitions.put(value.id(), value);
      definitionCreates++;
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
      implementationCreates++;
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
    public ProviderBinding create(ProviderBinding value) {
      bindings.put(value.id(), value);
      bindingCreates++;
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
      List<ProviderBinding> result = new ArrayList<>();
      bindings.values().stream()
          .filter(value -> value.status() == ProviderRegistrationStatus.ACTIVE)
          .filter(value -> value.organizationId().equals(query.organizationId()))
          .filter(value -> value.target().teamId().equals(query.teamId()))
          .filter(value -> value.target().workspaceId().equals(query.workspaceId()))
          .filter(value -> value.owner().equals(query.owner()))
          .filter(value -> value.providerType() == query.providerType())
          .filter(value -> value.executionIdentity().equals(query.executionIdentity()))
          .forEach(result::add);
      return List.copyOf(result);
    }

    @Override
    public Team create(Team value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Team> findById(OrganizationId requestedOrganizationId, TeamId id) {
      return Optional.of(team.team())
          .filter(value -> value.organizationId().equals(requestedOrganizationId))
          .filter(value -> value.id().equals(id));
    }

    @Override
    public Optional<UninitializedTeam> findUninitializedById(
        OrganizationId requestedOrganizationId, TeamId id) {
      return Optional.empty();
    }

    @Override
    public Workspace create(Workspace value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Workspace> findById(
        OrganizationId requestedOrganizationId, WorkspaceId id) {
      return Optional.of(team.defaultWorkspace())
          .filter(value -> value.scope().organizationId().equals(requestedOrganizationId))
          .filter(value -> value.id().equals(id));
    }

    @Override
    public List<TeamMember> findByTeam(
        OrganizationId requestedOrganizationId, TeamId requestedTeamId) {
      return team.team().organizationId().equals(requestedOrganizationId)
              && team.team().id().equals(requestedTeamId)
          ? List.of(team.ownerMember())
          : List.of();
    }
  }

  private static final class EmptyConnectionRepository implements ConnectionRepository {
    @Override
    public io.crewscope.domain.provider.Connection create(
        io.crewscope.domain.provider.Connection connection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.crewscope.domain.provider.Connection update(
        io.crewscope.domain.provider.Connection connection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<io.crewscope.domain.provider.Connection> findById(
        OrganizationId organizationId, io.crewscope.domain.provider.ConnectionId id) {
      return Optional.empty();
    }

    @Override
    public List<io.crewscope.domain.provider.Connection> findByOwner(ProviderOwner owner) {
      return List.of();
    }
  }

  private static final class EmptyConnectionGrantRepository implements ConnectionGrantRepository {
    @Override
    public io.crewscope.domain.provider.ConnectionGrant create(
        io.crewscope.domain.provider.ConnectionGrant grant) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.crewscope.domain.provider.ConnectionGrant update(
        io.crewscope.domain.provider.ConnectionGrant grant) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<io.crewscope.domain.provider.ConnectionGrant> findById(
        OrganizationId organizationId,
        io.crewscope.domain.provider.ConnectionGrantId id) {
      return Optional.empty();
    }

    @Override
    public List<io.crewscope.domain.provider.ConnectionGrant> findByConnectionAndGrantee(
        io.crewscope.domain.provider.ConnectionId connectionId, ProviderOwner grantee) {
      return List.of();
    }
  }
}
