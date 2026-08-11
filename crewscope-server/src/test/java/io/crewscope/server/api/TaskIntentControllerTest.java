package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.conversation.TaskIntentConfirmationCommandPort;
import io.crewscope.application.conversation.TaskIntentConfirmationResult;
import io.crewscope.application.conversation.TaskIntentConfirmationPreview;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentCandidate;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves M2-A05 TaskIntent resource, command header and confirmation preview contracts. */
class TaskIntentControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-11T05:00:00Z");

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
          "Plan M2-A05",
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
  private final TaskIntentProposal proposal =
      TaskIntentProposal.create(
          conversation.conversation(),
          project,
          "Ship TaskIntent review",
          List.of("Owner can review"),
          TaskIntentCandidate.user(owner, team.ownerMember()),
          Optional.of(
              TaskIntentCandidate.agent(team.ownerPersonalAgent().agentPrincipal())),
          Optional.empty());
  private final TaskIntent ready =
      TaskIntent.draft(
              TaskIntentId.generate(),
              conversation.conversation(),
              conversation.agentParticipant(),
              team.ownerPersonalAgent().agentPrincipal(),
              proposal,
              NOW)
          .markReady(0, team.ownerPersonalAgent().agentPrincipal(), NOW);

  private TaskIntentApplicationService service;
  private TaskIntentConfirmationCommandPort confirmationService;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(TaskIntentApplicationService.class);
    confirmationService = mock(TaskIntentConfirmationCommandPort.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(owner, false));
    client =
        WebTestClient.bindToController(
                new TaskIntentController(service, confirmationService, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void confirmsWithOnlyTrustedHeadersAndNoClientAuthoredWorkItemFields() {
    CommandReceipt receipt = receipt(2);
    when(confirmationService.confirm(any(), eq(team.team().id()), any(), any()))
        .thenReturn(
            CommandExecution.completed(
                new TaskIntentConfirmationResult(ready, WorkItemId.generate()), receipt));

    client
        .post()
        .uri(resource() + "/confirmations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "task-intent-confirm-http-1")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(2);

    verify(confirmationService).confirm(any(), eq(team.team().id()), any(), any());
  }

  @Test
  void rejectsAnyNonEmptyConfirmationRequestBody() {
    client
        .post()
        .uri(resource() + "/confirmations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "task-intent-confirm-http-body")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"ownerMemberId\":\"" + team.ownerMember().id() + "\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");

    verifyNoInteractions(confirmationService);
  }

  @Test
  void getsTheNestedResourceWithStrongEtagAndNoStore() {
    when(service.get(any(), eq(organizationId), eq(team.team().id()), any()))
        .thenReturn(ready);

    client
        .get()
        .uri(resource())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectHeader()
        .valueEquals(ApiHeaders.ETAG, "\"1\"")
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo(ready.id().toString())
        .jsonPath("$.status")
        .isEqualTo("READY")
        .jsonPath("$.proposal.owner.principalId")
        .isEqualTo(owner.id().toString())
        .jsonPath("$.proposal.executor.principalType")
        .isEqualTo("PERSONAL_AGENT");
  }

  @Test
  void previewsConfirmationFromCurrentFactsWithoutExposingAConfirmationCommand() {
    when(service.previewConfirmation(
            any(), eq(organizationId), eq(team.team().id()), any(), eq(ready.version())))
        .thenReturn(new TaskIntentConfirmationPreview(ready, proposal, owner.id()));

    client
        .post()
        .uri(resource() + "/confirmation-previews")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectHeader()
        .valueEquals(ApiHeaders.ETAG, "\"1\"")
        .expectBody()
        .jsonPath("$.confirmable")
        .isEqualTo(true)
        .jsonPath("$.confirmingPrincipalId")
        .isEqualTo(owner.id().toString())
        .jsonPath("$.proposalRevision")
        .isEqualTo(1);
  }

  @Test
  void acceptsCompleteRevisionAndRejectionCommands() {
    CommandReceipt revisionReceipt = receipt(3);
    CommandReceipt rejectionReceipt = receipt(2);
    when(service.revise(any(), eq(team.team().id()), any(), any(), eq(ready.version())))
        .thenReturn(CommandExecution.completed(ready, revisionReceipt));
    when(service.reject(any(), eq(team.team().id()), any(), any(), eq(ready.version())))
        .thenReturn(CommandExecution.replayed(rejectionReceipt));

    client
        .post()
        .uri(resource() + "/revisions")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "task-intent-revision-http-1")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(revisionJson())
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(3);

    client
        .post()
        .uri(resource() + "/rejections")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "task-intent-rejection-http-1")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Wrong target\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(2);

    verify(service).revise(any(), eq(team.team().id()), any(), any(), eq(1L));
    verify(service).reject(any(), eq(team.team().id()), any(), any(), eq(1L));
  }

  @Test
  void requiresCommandConcurrencyHeadersAndOneStrongVersionEtag() {
    client
        .post()
        .uri(resource() + "/revisions")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "task-intent-revision-http-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(revisionJson())
        .exchange()
        .expectStatus()
        .isEqualTo(428)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("precondition_required");

    for (String invalid : List.of("W/\"1\"", "\"1\", \"2\"", "*")) {
      client
          .post()
          .uri(resource() + "/confirmation-previews")
          .header(ApiHeaders.IF_MATCH, invalid)
          .exchange()
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("invalid_if_match");
    }

    client
        .post()
        .uri(resource() + "/rejections")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Wrong target\"}")
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");
  }

  @Test
  void rejectsInvalidStructuredProposalBeforeCallingTheApplicationService() {
    client
        .post()
        .uri(resource() + "/revisions")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "task-intent-revision-http-3")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "schemaVersion":"2",
              "objective":"Changed",
              "acceptanceCriteria":[],
              "workProjectId":"not-a-uuid",
              "ownerMemberId":"not-a-uuid"
            }
            """)
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");
  }

  private String resource() {
    return "/api/v1/organizations/"
        + organizationId
        + "/teams/"
        + team.team().id()
        + "/conversations/"
        + conversation.conversation().id()
        + "/task-intents/"
        + ready.id();
  }

  private String revisionJson() {
    return """
        {
          "schemaVersion":"1",
          "objective":"Changed objective",
          "acceptanceCriteria":["Changed criterion"],
          "workProjectId":"%s",
          "ownerMemberId":"%s",
          "executorPrincipalId":"%s"
        }
        """
        .formatted(
            project.id(),
            team.ownerMember().id(),
            team.ownerPersonalAgent().agentPrincipal().id());
  }

  private static CommandReceipt receipt(long version) {
    return new CommandReceipt(
        UUID.randomUUID(), UUID.randomUUID(), version, UUID.randomUUID());
  }
}
