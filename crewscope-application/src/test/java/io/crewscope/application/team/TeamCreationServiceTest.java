package io.crewscope.application.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.model.PlatformModelCatalogInitializer;
import io.crewscope.application.provider.TeamProviderInitializer;
import io.crewscope.application.teamobserver.TeamObserverInitializer;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workspace.PersonalAgentInitialization;
import io.crewscope.domain.workspace.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TeamCreationServiceTest {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T18:00:00.123456Z");

  @Test
  void persistsTheCompleteFoundationInsideOneRequiredTransaction() {
    RecordingTransactionExecutor transaction = new RecordingTransactionExecutor();
    RecordingRepositories repositories = new RecordingRepositories(transaction);
    TeamCreationService service = service(repositories, transaction);
    Principal creator = activeUser();

    TeamInitialization result = service.create(creator, new CreateTeamCommand("  Platform Crew  "));

    assertEquals(
        List.of("team", "workspace", "member", "roles", "ownerRole", "personalAgent"),
        repositories.writes);
    assertEquals(1, transaction.requiredCalls);
    assertFalse(transaction.inTransaction);
    assertEquals("Platform Crew", result.team().name());
    assertEquals(CREATED_AT, result.team().audit().createdAt());
    assertEquals(CREATED_AT, result.defaultWorkspace().audit().createdAt());
    assertEquals(creator.id(), result.ownerMember().userPrincipalId());
    assertTrue(result.ownerRole().isEffectiveAt(CREATED_AT));
    assertEquals(
        creator.id(),
        result.ownerPersonalAgent().agentPrincipal().ownerPrincipalId().orElseThrow());
  }

  @Test
  void initializesTheBuiltInCatalogsAndObserverInsideTheTeamCreationTransaction() {
    RecordingTransactionExecutor transaction = new RecordingTransactionExecutor();
    RecordingRepositories repositories = new RecordingRepositories(transaction);
    List<String> initialized = new ArrayList<>();
    TeamProviderInitializer providers = (team, workspace, actor) -> {
      assertTrue(transaction.inTransaction);
      initialized.add("providers");
    };
    AgentTemplateCatalogInitializer templates = (organizationId, actor, occurredAt) -> {
      assertTrue(transaction.inTransaction);
      initialized.add("templates");
    };
    PlatformModelCatalogInitializer models = (actor, occurredAt) -> {
      assertTrue(transaction.inTransaction);
      initialized.add("models");
    };
    TeamObserverInitializer observer = (team, workspace, ownerMember, ownerUser) -> {
      assertTrue(transaction.inTransaction);
      assertEquals(ownerUser.id(), ownerMember.userPrincipalId());
      initialized.add("observer");
    };
    TimeProvider timeProvider =
        TimeProvider.from(
            Clock.fixed(Instant.parse("2026-08-07T18:00:00.123456789Z"), ZoneOffset.UTC));
    TeamCreationService service =
        new TeamCreationService(
            repositories,
            repositories,
            repositories,
            repositories,
            repositories,
            repositories,
            providers,
            transaction,
            timeProvider,
            models,
            templates,
            observer);

    service.create(activeUser(), new CreateTeamCommand("Platform Crew"));

    assertEquals(List.of("providers", "models", "templates", "observer"), initialized);
    assertEquals(1, transaction.requiredCalls);
  }

  @Test
  void rejectsInactiveCreatorBeforeAnyPersistenceWrite() {
    RecordingTransactionExecutor transaction = new RecordingTransactionExecutor();
    RecordingRepositories repositories = new RecordingRepositories(transaction);
    TeamCreationService service = service(repositories, transaction);
    Principal disabled =
        activeUser()
            .transitionTo(PrincipalStatus.DISABLED, UtcTimestamp.parse("2026-08-07T18:00:01Z"));

    DomainValidationException failure =
        assertThrows(
            DomainValidationException.class,
            () -> service.create(disabled, new CreateTeamCommand("Platform Crew")));

    assertEquals("teamMember.userPrincipalId", failure.error().details().get("field"));
    assertTrue(repositories.writes.isEmpty());
    assertFalse(transaction.inTransaction);
  }

  @Test
  void stopsTheInitializationWhenARepositoryWriteFails() {
    RecordingTransactionExecutor transaction = new RecordingTransactionExecutor();
    RecordingRepositories repositories = new RecordingRepositories(transaction);
    repositories.failOnRoles = true;
    TeamCreationService service = service(repositories, transaction);

    assertThrows(
        IllegalStateException.class,
        () -> service.create(activeUser(), new CreateTeamCommand("Platform Crew")));

    assertEquals(List.of("team", "workspace", "member", "roles"), repositories.writes);
    assertFalse(repositories.writes.contains("ownerRole"));
    assertFalse(transaction.inTransaction);
  }

  @Test
  void completesALegacyTeamWithTheSameAtomicFoundation() {
    RecordingTransactionExecutor transaction = new RecordingTransactionExecutor();
    RecordingRepositories repositories = new RecordingRepositories(transaction);
    TeamCreationService service = service(repositories, transaction);
    Principal owner = activeUser();
    Team source = TeamInitialization.create(owner, "Legacy Crew", CREATED_AT).team();
    UninitializedTeam legacy =
        new UninitializedTeam(
            source.id(),
            source.organizationId(),
            source.name(),
            source.status(),
            source.version(),
            source.audit());

    TeamInitialization result = service.completeLegacy(legacy, owner, owner);

    assertEquals(
        List.of("teamUpdate", "workspace", "member", "roles", "ownerRole", "personalAgent"),
        repositories.writes);
    assertEquals(legacy.id(), result.team().id());
    assertEquals(1, result.team().version());
    assertEquals(owner.id(), result.ownerMember().userPrincipalId());
  }

  private static TeamCreationService service(
      RecordingRepositories repositories, RecordingTransactionExecutor transactionExecutor) {
    TimeProvider timeProvider =
        TimeProvider.from(
            Clock.fixed(Instant.parse("2026-08-07T18:00:00.123456789Z"), ZoneOffset.UTC));
    return new TeamCreationService(
        repositories,
        repositories,
        repositories,
        repositories,
        repositories,
        repositories,
        (team, workspace, actor) -> {},
        transactionExecutor,
        timeProvider,
        (actor, occurredAt) -> {},
        (organizationId, actor, occurredAt) -> {},
        (team, workspace, ownerMember, ownerUser) -> {});
  }

  private static Principal activeUser() {
    return Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(ORGANIZATION_ID),
        PrincipalType.USER,
        Optional.empty(),
        "Creator",
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        CREATED_AT);
  }

  private static final class RecordingTransactionExecutor implements TransactionExecutor {

    private boolean inTransaction;
    private int requiredCalls;

    @Override
    public <T> T required(Supplier<T> operation) {
      requiredCalls++;
      inTransaction = true;
      try {
        return operation.get();
      } finally {
        inTransaction = false;
      }
    }
  }

  private static final class RecordingRepositories
      implements TeamRepository,
          WorkspaceRepository,
          TeamMemberRepository,
          TeamRoleRepository,
          MemberRoleRepository,
          DefaultPersonalAgentRepository {

    private final RecordingTransactionExecutor transaction;
    private final List<String> writes = new ArrayList<>();
    private boolean failOnRoles;

    private RecordingRepositories(RecordingTransactionExecutor transaction) {
      this.transaction = transaction;
    }

    @Override
    public Team create(Team team) {
      record("team");
      return team;
    }

    @Override
    public Team update(Team team) {
      record("teamUpdate");
      return team;
    }

    @Override
    public Workspace create(Workspace workspace) {
      record("workspace");
      return workspace;
    }

    @Override
    public TeamMember create(TeamMember member) {
      record("member");
      return member;
    }

    @Override
    public List<TeamRole> createAll(List<TeamRole> roles) {
      record("roles");
      if (failOnRoles) {
        throw new IllegalStateException("role persistence failed");
      }
      return List.copyOf(roles);
    }

    @Override
    public MemberRole create(MemberRole memberRole) {
      record("ownerRole");
      return memberRole;
    }

    @Override
    public PersonalAgentInitialization initializeIfAbsent(PersonalAgentInitialization candidate) {
      record("personalAgent");
      return candidate;
    }

    private void record(String write) {
      assertTrue(transaction.inTransaction, "all Team foundation writes require a transaction");
      writes.add(write);
    }
  }
}
