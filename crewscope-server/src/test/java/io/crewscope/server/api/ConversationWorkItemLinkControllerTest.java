package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.conversation.ConversationWorkItemAssociation;
import io.crewscope.application.conversation.ConversationWorkItemQueryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.ConversationWorkItemLink;
import io.crewscope.domain.conversation.ConversationWorkItemLinkOrigin;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the M2-A07 bidirectional route shape and no-store response contract. */
class ConversationWorkItemLinkControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-11T11:00:00Z");

  private final OrganizationId organizationId = OrganizationId.generate();
  private final Principal owner =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          NOW);
  private final TeamInitialization team = TeamInitialization.create(owner, "CrewScope", NOW);
  private final PersonalConversationInitialization conversation =
      PersonalConversationInitialization.start(
          ConversationId.generate(),
          team.defaultWorkspace(),
          team.ownerMember(),
          owner,
          team.ownerPersonalAgent(),
          "M2-A07 discussion",
          ConversationVisibility.PRIVATE,
          NOW);
  private final WorkProject project =
      WorkProject.create(
          WorkProjectId.generate(),
          new WorkProjectKey("CRW"),
          "CrewScope",
          team.team(),
          team.defaultWorkspace(),
          owner,
          NOW);
  private final WorkItem workItem =
      WorkItem.createNative(
          WorkItemId.generate(),
          project,
          new WorkItemKey("CRW-1"),
          WorkItemType.TASK,
          "Implement atomic confirmation",
          Optional.empty(),
          WorkItemPriority.MEDIUM,
          Set.of(),
          Optional.empty(),
          owner,
          NOW);
  private final ConversationWorkItemAssociation association =
      new ConversationWorkItemAssociation(
          ConversationWorkItemLink.link(
              conversation.conversation(),
              workItem,
              ConversationWorkItemLinkOrigin.TASK_INTENT_CONFIRMATION,
              owner,
              NOW),
          conversation.conversation(),
          workItem);

  private ConversationWorkItemQueryService service;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(ConversationWorkItemQueryService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(owner, false));
    client =
        WebTestClient.bindToController(new ConversationWorkItemLinkController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void readsBothDirectionsFromTheSameAssociationFact() {
    when(service.byConversation(
            any(), eq(organizationId), eq(team.team().id()), eq(conversation.conversation().id())))
        .thenReturn(List.of(association));
    when(service.byWorkItem(
            any(),
            eq(organizationId),
            eq(team.team().id()),
            eq(project.id()),
            eq(workItem.id())))
        .thenReturn(List.of(association));

    client
        .get()
        .uri(
            "/api/v1/organizations/%s/teams/%s/conversations/%s/work-items"
                .formatted(
                    organizationId, team.team().id(), conversation.conversation().id()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$[0].workItem.id")
        .isEqualTo(workItem.id().toString())
        .jsonPath("$[0].origin")
        .isEqualTo("TASK_INTENT_CONFIRMATION");

    client
        .get()
        .uri(
            "/api/v1/organizations/%s/teams/%s/work-projects/%s/work-items/%s/conversations"
                .formatted(organizationId, team.team().id(), project.id(), workItem.id()))
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$[0].conversation.id")
        .isEqualTo(conversation.conversation().id().toString())
        .jsonPath("$[0].workItem.key")
        .isEqualTo("CRW-1");
  }

  @Test
  void rejectsMalformedNestedIdentifiersWithoutCallingTheService() {
    client
        .get()
        .uri(
            "/api/v1/organizations/%s/teams/%s/conversations/not-a-uuid/work-items"
                .formatted(organizationId, team.team().id()))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");
  }
}
