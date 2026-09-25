package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.DelegationContext;
import io.crewscope.application.task.DelegationContextService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the delegation-context wire shape and the same visibility boundary as GET responsibilities. */
class DelegationContextControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final UtcTimestamp now = UtcTimestamp.parse("2026-09-25T11:00:00Z");
  private final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          now);
  private final TeamId teamId = TeamId.generate();
  private final WorkProjectId projectId = WorkProjectId.generate();
  private final WorkItemId workItemId = WorkItemId.generate();
  private final WorkItemScope scope = new WorkItemScope(
      organizationId, teamId, WorkspaceId.generate(), projectId);
  private final WorkItem item =
      WorkItem.reconstitute(
          workItemId,
          scope,
          new WorkItemKey("CRW-701"),
          "Delegation context API",
          WorkItemStatus.READY,
          7,
          AuditMetadata.createdBy(actor.id(), now));
  private final ResponsibilityAssignment ownerAssignment =
      ResponsibilityAssignment.reconstitute(
          ResponsibilityAssignmentId.generate(),
          scope,
          workItemId,
          ResponsibilityRole.OWNER,
          actor.id(),
          actor.type(),
          Optional.of(io.crewscope.domain.team.TeamMemberId.generate()),
          io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus.ACTIVE,
          actor.id(),
          now,
          now,
          Optional.empty(),
          Optional.empty(),
          0,
          AuditMetadata.createdBy(actor.id(), now));
  private final AgentProfileId profileId = AgentProfileId.generate();
  private final DelegationContext context =
      new DelegationContext(
          new DelegationContext.WorkItemLine(
              workItemId, projectId, 7, "Delegation context API", WorkItemStatus.READY),
          List.of(new DelegationContext.ResponsibilityLine(
              ownerAssignment.id(),
              0,
              ResponsibilityRole.OWNER,
              actor.id(),
              "USER",
              "Owner",
              Optional.empty())),
          List.of(
              new DelegationContext.AgentCandidate(
                  profileId, 2, PrincipalId.generate(), "Owner Agent", "USER", "SPECIALIST",
                  DelegationContext.STATE_ASSIGNED, Optional.empty()),
              new DelegationContext.AgentCandidate(
                  AgentProfileId.generate(), 1, PrincipalId.generate(), "Team Agent", "TEAM",
                  "TEAM_COORDINATOR",
                  DelegationContext.STATE_EXECUTOR_CONFLICT,
                  Optional.of("已有其他执行者责任——需先显式释放再分配"))),
          DelegationContextServiceDefaultsSnapshotFixture.SNAPSHOT,
          true,
          new DelegationContext.Permissions(true, false));

  private DelegationContextService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(DelegationContextService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new DelegationContextController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void servesTheDelegationContextWireShape() {
    when(service.getContext(
            argThat(access -> access != null),
            argThat(team -> team != null),
            argThat(project -> project != null),
            argThat(workItem -> workItem != null)))
        .thenReturn(context);

    client
        .get()
        .uri(root())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.workItem.title")
        .isEqualTo("Delegation context API")
        .jsonPath("$.workItem.status")
        .isEqualTo("READY")
        .jsonPath("$.workItem.version")
        .isEqualTo(7)
        .jsonPath("$.responsibilities[0].role")
        .isEqualTo("OWNER")
        .jsonPath("$.responsibilities[0].actorDisplayName")
        .isEqualTo("Owner")
        .jsonPath("$.candidates[0].state")
        .isEqualTo("ASSIGNED")
        .jsonPath("$.candidates[1].state")
        .isEqualTo("EXECUTOR_CONFLICT")
        .jsonPath("$.candidates[1].reason")
        .isEqualTo("已有其他执行者责任——需先显式释放再分配")
        .jsonPath("$.defaults.version")
        .isEqualTo(3)
        .jsonPath("$.defaults.repositoryBindingId.source")
        .isEqualTo("PROJECT_DEFAULT")
        .jsonPath("$.defaults.repositoryBindingId.value")
        .isNotEmpty()
        .jsonPath("$.defaults.buildProfile.value.key")
        .isEqualTo("maven-java-17")
        .jsonPath("$.defaults.agentProfileId.availability")
        .isEqualTo("AVAILABLE")
        .jsonPath("$.activeExecution")
        .isEqualTo(true)
        .jsonPath("$.permissions.canAssignResponsibility")
        .isEqualTo(true)
        .jsonPath("$.permissions.canDelegate")
        .isEqualTo(false);
  }

  @Test
  void anInvisibleWorkItemStaysNotFound() {
    when(service.getContext(any(), any(), any(), any()))
        .thenThrow(new AggregateNotFoundException("WorkItem", workItemId));

    client
        .get()
        .uri(root())
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void aMemberOutsideTheCandidateBoundaryIsForbidden() {
    when(service.getContext(any(), any(), any(), any()))
        .thenThrow(new PolicyDeniedException("read this Team's Agent candidates"));

    client
        .get()
        .uri(root())
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void anInvalidRouteIdentifierIsRejected() {
    client
        .get()
        .uri("/api/v1/organizations/" + organizationId + "/teams/" + teamId
            + "/work-projects/" + projectId + "/work-items/not-a-uuid/delegation-context")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  private String root() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
        + "/work-projects/" + projectId + "/work-items/" + workItemId + "/delegation-context";
  }

  /** Keeps the wire fixture next to the test without leaking controller-only concerns upward. */
  private static final class DelegationContextServiceDefaultsSnapshotFixture {

    private static final DelegationContext.DefaultsSnapshot SNAPSHOT =
        new DelegationContext.DefaultsSnapshot(
            3,
            Optional.of(RepositoryBindingId.generate()),
            Optional.of(2L),
            Optional.of(new RepositoryBranchName("main")),
            Optional.of(new BuildProfileReference(
                "maven-java-17", 1,
                new TaskFactHash(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))),
            Optional.of(AgentProfileId.generate()),
            Optional.of(4L));
  }
}
