package io.crewscope.application.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalProvisioningResult;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.workitem.WorkItemCollaborationTestFixture;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ResponsibilityVersionConflictException;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.responsibility.ReviewerPolicyViolationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves the authenticated A06 responsibility command and query boundary. */
class ResponsibilityCommandAndQueryServiceTest {

  @Test
  void assignsAndReplacesOwnerWithAnAbaSafeExpectationAndIdempotentEvent() {
    Fixture fixture = new Fixture();
    Member first = fixture.member("First Owner");
    var context = fixture.store.commandContext("owner-first");

    var assigned =
        fixture.commands.replaceOwner(
            context,
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id(),
            new ReplaceOwnerCommand(first.principal.id(), ActiveOwnerExpectation.none()));
    var replayed =
        fixture.commands.replaceOwner(
            context,
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id(),
            new ReplaceOwnerCommand(first.principal.id(), ActiveOwnerExpectation.none()));
    Member second = fixture.member("Second Owner");
    ResponsibilityAssignment current = assigned.result().orElseThrow().active();
    var replaced =
        fixture.commands.replaceOwner(
            fixture.store.commandContext("owner-second"),
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id(),
            new ReplaceOwnerCommand(second.principal.id(), ActiveOwnerExpectation.current(current)));

    assertTrue(replayed.replayed());
    assertEquals(2, fixture.assignments.values.size());
    assertEquals(second.principal.id(), replaced.result().orElseThrow().active().actorPrincipalId());
    assertEquals("WORK_ITEM_OWNER_ASSIGNED", fixture.store.events.get(0).eventType().value());
    assertEquals("WORK_ITEM_OWNER_REPLACED", fixture.store.events.get(1).eventType().value());
    assertThrows(
        ResponsibilityVersionConflictException.class,
        () ->
            fixture.commands.replaceOwner(
                fixture.store.commandContext("owner-stale"),
                fixture.store.initialization.team().id(),
                fixture.store.project.id(),
                fixture.store.item.id(),
                new ReplaceOwnerCommand(first.principal.id(), ActiveOwnerExpectation.current(current))));
  }

  @Test
  void assignsAndReleasesAnExecutorWithOptimisticConcurrency() {
    Fixture fixture = new Fixture();
    Member executor = fixture.member("Executor");
    ResponsibilityAssignment assigned =
        fixture.commands
            .assignExecutor(
                fixture.store.commandContext("executor-1"),
                fixture.store.initialization.team().id(),
                fixture.store.project.id(),
                fixture.store.item.id(),
                new AssignResponsibilityCommand(executor.principal.id()))
            .result()
            .orElseThrow();

    ResponsibilityAssignment released =
        fixture.commands
            .release(
                fixture.store.commandContext("executor-release"),
                fixture.store.initialization.team().id(),
                fixture.store.project.id(),
                fixture.store.item.id(),
                assigned.id(),
                new ReleaseResponsibilityCommand(0))
            .result()
            .orElseThrow();

    assertEquals(1, released.version());
    assertEquals("WORK_ITEM_EXECUTOR_ASSIGNED", fixture.store.events.get(0).eventType().value());
    assertEquals("WORK_ITEM_RESPONSIBILITY_RELEASED", fixture.store.events.get(1).eventType().value());
    assertThrows(
        OptimisticLockConflictException.class,
        () ->
            fixture.commands.release(
                fixture.store.commandContext("executor-stale-release"),
                fixture.store.initialization.team().id(),
                fixture.store.project.id(),
                fixture.store.item.id(),
                assigned.id(),
                new ReleaseResponsibilityCommand(0)));
  }

  @Test
  void enforcesGateReviewerSeparationAndSupportsAdvisorySpecialists() {
    Fixture fixture = new Fixture();
    Member owner = fixture.member("Owner Candidate");
    ResponsibilityAssignment ownerAssignment =
        fixture.commands
            .replaceOwner(
                fixture.store.commandContext("owner-for-review"),
                fixture.store.initialization.team().id(),
                fixture.store.project.id(),
                fixture.store.item.id(),
                new ReplaceOwnerCommand(owner.principal.id(), ActiveOwnerExpectation.none()))
            .result()
            .orElseThrow()
            .active();

    assertThrows(
        ReviewerPolicyViolationException.class,
        () ->
            fixture.commands.assignGateReviewer(
                fixture.store.commandContext("conflicting-reviewer"),
                fixture.store.initialization.team().id(),
                fixture.store.project.id(),
                fixture.store.item.id(),
                new AssignResponsibilityCommand(owner.principal.id())));

    Member independent = fixture.member("Independent Reviewer");
    var gate =
        fixture.commands.assignGateReviewer(
            fixture.store.commandContext("gate-reviewer"),
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id(),
            new AssignResponsibilityCommand(independent.principal.id()));
    Principal specialist = fixture.specialist("Review Specialist");
    var advisory =
        fixture.commands.assignAdvisoryReviewer(
            fixture.store.commandContext("advisory-reviewer"),
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id(),
            new AssignResponsibilityCommand(specialist.id()));

    assertEquals(ownerAssignment.id(), fixture.assignments.findActiveOwner(fixture.store.organizationId, fixture.store.item.id()).orElseThrow().id());
    assertEquals(ResponsibilityRole.REVIEWER, gate.result().orElseThrow().assignment().role());
    assertEquals(PrincipalType.SPECIALIST_AGENT, advisory.result().orElseThrow().actorType());
    assertEquals("WORK_ITEM_GATE_REVIEWER_ASSIGNED", fixture.store.events.get(1).eventType().value());
  }

  @Test
  void returnsActiveAssignmentsWithServerResolvedDisplayNamesAndChecksProjectPermission() {
    Fixture fixture = new Fixture();
    Member executor = fixture.member("Visible Executor");
    fixture.commands.assignExecutor(
        fixture.store.commandContext("visible-executor"),
        fixture.store.initialization.team().id(),
        fixture.store.project.id(),
        fixture.store.item.id(),
        new AssignResponsibilityCommand(executor.principal.id()));

    List<ResponsibilityAssignmentView> views =
        fixture.queries.listActive(
            fixture.store.access(),
            fixture.store.organizationId,
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id());

    assertEquals("Visible Executor", views.get(0).actorDisplayName());

    Fixture denied = new Fixture();
    denied.store.useProjectRole(WorkProjectId.generate());
    Member target = denied.member("Denied Target");
    assertThrows(
        PolicyDeniedException.class,
        () ->
            denied.commands.assignExecutor(
                denied.store.commandContext("wrong-project-permission"),
                denied.store.initialization.team().id(),
                denied.store.project.id(),
                denied.store.item.id(),
                new AssignResponsibilityCommand(target.principal.id())));
  }

  @Test
  void exposesOnlyTheCurrentInScopeAgentProfileBehindAnAgentExecutor() {
    Fixture fixture = new Fixture();
    Principal agent = fixture.personalAgent("Visible Personal Agent");
    fixture.commands.assignExecutor(
        fixture.store.commandContext("visible-agent-executor"),
        fixture.store.initialization.team().id(),
        fixture.store.project.id(),
        fixture.store.item.id(),
        new AssignResponsibilityCommand(agent.id()));
    AgentProfile profile = mock(AgentProfile.class);
    AgentProfileId profileId = AgentProfileId.generate();
    when(profile.id()).thenReturn(profileId);
    when(profile.scope()).thenReturn(WorkspaceScope.team(
        fixture.store.organizationId, fixture.store.initialization.team().id()));
    when(profile.workspaceId()).thenReturn(fixture.store.item.scope().workspaceId());
    when(fixture.profiles.findActiveByAgentPrincipalId(
        fixture.store.organizationId, agent.id())).thenReturn(Optional.of(profile));

    ResponsibilityAssignmentView view = fixture.queries.listActive(
            fixture.store.access(),
            fixture.store.organizationId,
            fixture.store.initialization.team().id(),
            fixture.store.project.id(),
            fixture.store.item.id())
        .get(0);

    assertEquals(profileId, view.actorAgentProfileId().orElseThrow());
  }

  private record Member(Principal principal, TeamMember membership) {}

  private static final class Fixture {
    private final WorkItemCollaborationTestFixture store =
        new WorkItemCollaborationTestFixture();
    private final AssignmentRepository assignments = new AssignmentRepository();
    private final PrincipalStore principals = new PrincipalStore();
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final ResponsibilityAssignmentService assignmentService =
        new ResponsibilityAssignmentService(assignments, store, () -> store.NOW);
    private final GateReviewerAssignmentService reviewerService =
        new GateReviewerAssignmentService(assignments, store.membershipQuery(), store, () -> store.NOW);
    private final ResponsibilityCommandService commands;
    private final ResponsibilityQueryService queries;

    private Fixture() {
      principals.values.put(store.actor.id(), store.actor);
      commands =
          new ResponsibilityCommandService(
              assignments,
              assignmentService,
              reviewerService,
              item -> ReviewerEligibilityPolicy.strict(),
              store.accessPolicy(),
              principals,
              store.membershipQuery(),
              store,
              store,
              store,
              store,
              () -> store.NOW);
      queries =
          new ResponsibilityQueryService(
              assignments, principals, profiles, store.accessPolicy(), store);
    }

    private Member member(String displayName) {
      Principal principal =
          Principal.create(
              PrincipalId.generate(),
              PrincipalScope.organization(store.organizationId),
              PrincipalType.USER,
              Optional.empty(),
              displayName,
              Optional.empty(),
              PrincipalVisibility.ORGANIZATION,
              store.NOW);
      TeamMember member =
          store.initialization.team().joinMember(
              TeamMemberId.generate(), principal, TeamJoinMethod.OIDC, store.NOW);
      principals.values.put(principal.id(), principal);
      store.members = append(store.members, member);
      return new Member(principal, member);
    }

    private Principal specialist(String displayName) {
      Principal principal =
          Principal.create(
              PrincipalId.generate(),
              PrincipalScope.team(store.organizationId, store.initialization.team().id()),
              PrincipalType.SPECIALIST_AGENT,
              Optional.of(store.actor.id()),
              displayName,
              Optional.empty(),
              PrincipalVisibility.TEAM,
              store.NOW);
      principals.values.put(principal.id(), principal);
      return principal;
    }

    private Principal personalAgent(String displayName) {
      Principal principal =
          Principal.create(
              PrincipalId.generate(),
              PrincipalScope.team(store.organizationId, store.initialization.team().id()),
              PrincipalType.PERSONAL_AGENT,
              Optional.of(store.actor.id()),
              displayName,
              Optional.empty(),
              PrincipalVisibility.TEAM,
              store.NOW);
      principals.values.put(principal.id(), principal);
      return principal;
    }

    private static <T> List<T> append(List<T> values, T value) {
      List<T> changed = new ArrayList<>(values);
      changed.add(value);
      return List.copyOf(changed);
    }
  }

  private static final class PrincipalStore implements PrincipalRepository {
    private final Map<PrincipalId, Principal> values = new LinkedHashMap<>();

    @Override
    public Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId) {
      return Optional.ofNullable(values.get(principalId))
          .filter(value -> value.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<Principal> findByExternalIdentity(
        OrganizationId organizationId, String provider, String subject) {
      return Optional.empty();
    }

    @Override
    public boolean organizationExists(OrganizationId organizationId) {
      return true;
    }

    @Override
    public PrincipalProvisioningResult provisionUser(Principal candidate) {
      values.put(candidate.id(), candidate);
      return new PrincipalProvisioningResult(candidate, true);
    }
  }

  private static final class AssignmentRepository
      implements ResponsibilityAssignmentRepository {
    private final Map<ResponsibilityAssignmentId, ResponsibilityAssignment> values =
        new LinkedHashMap<>();

    @Override
    public void lockResponsibilityChain(
        OrganizationId organizationId, WorkItemId workItemId) {}

    @Override
    public ResponsibilityAssignment create(ResponsibilityAssignment assignment) {
      values.put(assignment.id(), assignment);
      return assignment;
    }

    @Override
    public ResponsibilityAssignment update(ResponsibilityAssignment assignment) {
      ResponsibilityAssignment current = values.get(assignment.id());
      long expected = assignment.version() - 1;
      if (current == null || current.version() != expected) {
        throw new OptimisticLockConflictException(
            "ResponsibilityAssignment",
            assignment.id(),
            expected,
            current == null ? 0 : current.version());
      }
      values.put(assignment.id(), assignment);
      return assignment;
    }

    @Override
    public Optional<ResponsibilityAssignment> findById(
        OrganizationId organizationId, ResponsibilityAssignmentId id) {
      return Optional.ofNullable(values.get(id))
          .filter(value -> value.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<ResponsibilityAssignment> findActiveOwner(
        OrganizationId organizationId, WorkItemId workItemId) {
      return findActiveByWorkItem(organizationId, workItemId).stream()
          .filter(value -> value.role() == ResponsibilityRole.OWNER)
          .findFirst();
    }

    @Override
    public List<ResponsibilityAssignment> findActiveByWorkItem(
        OrganizationId organizationId, WorkItemId workItemId) {
      return values.values().stream()
          .filter(ResponsibilityAssignment::isActive)
          .filter(value -> value.scope().organizationId().equals(organizationId))
          .filter(value -> value.workItemId().equals(workItemId))
          .toList();
    }

    @Override
    public Optional<ResponsibilityAssignment> findActive(
        OrganizationId organizationId,
        WorkItemId workItemId,
        ResponsibilityRole role,
        PrincipalId actorPrincipalId) {
      return findActiveByWorkItem(organizationId, workItemId).stream()
          .filter(value -> value.role() == role)
          .filter(value -> value.actorPrincipalId().equals(actorPrincipalId))
          .findFirst();
    }
  }
}
