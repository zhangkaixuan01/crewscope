package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.knowledge.DistillKnowledgeEntryCommand;
import io.crewscope.application.knowledge.KnowledgeDistillationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the A02b distillation HTTP contract: 202 receipt shape, bounds and error face. */
class KnowledgeDistillationControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T06:00:00Z");

  private final OrganizationId organizationId = OrganizationId.generate();
  private final Principal actor =
      Principal.create(
          PrincipalId.generate(),
          PrincipalScope.organization(organizationId),
          PrincipalType.USER,
          Optional.empty(),
          "Owner",
          Optional.empty(),
          PrincipalVisibility.ORGANIZATION,
          NOW);
  private final TeamInitialization initialization =
      TeamInitialization.create(actor, "Platform", NOW);
  private final TeamId teamId = initialization.team().id();
  private final TaskExecutionId executionId = TaskExecutionId.generate();

  private KnowledgeEntry distilled;
  private KnowledgeDistillationService service;
  private WebTestClient client;

  private String root() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId
        + "/knowledge/distillations";
  }

  private String body(String taskExecutionId, String entryKey, String category) {
    String categoryField = category == null ? "" : ",\"category\":\"" + category + "\"";
    return "{\"taskExecutionId\":\"" + taskExecutionId
        + "\",\"entryKey\":\"" + entryKey + "\"" + categoryField + "}";
  }

  @BeforeEach
  void setUp() {
    distilled =
        KnowledgeEntry.createDistilled(
            new TeamScope(organizationId, teamId),
            new KnowledgeEntryKey("postmortem-cache"),
            KnowledgeCategory.DECISION,
            "Postmortem: cache eviction storms",
            "Increase eviction jitter.",
            new io.crewscope.domain.knowledge.KnowledgeEntryOrigin(
                executionId.value(), 2),
            actor.id(),
            NOW);
    service = mock(KnowledgeDistillationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new KnowledgeDistillationController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void distillsThroughTheAcceptedReceiptContract() {
    AtomicReference<DistillKnowledgeEntryCommand> parsed = new AtomicReference<>();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.distill(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              parsed.set(invocation.getArgument(2, DistillKnowledgeEntryCommand.class));
              return CompletableFuture.completedFuture(
                  CommandExecution.completed(distilled, receipt));
            });

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "distill-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(executionId.value().toString(), "postmortem-cache", null))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.domainEventId")
        .isEqualTo(receipt.domainEventId().toString())
        .jsonPath("$.entryId")
        .isEqualTo(distilled.id().value().toString())
        .jsonPath("$.entryKey")
        .isEqualTo("postmortem-cache")
        .jsonPath("$.origin.taskExecutionId")
        .isEqualTo(executionId.value().toString())
        .jsonPath("$.origin.attempt")
        .isEqualTo(2)
        .jsonPath("$.indexStatus")
        .isEqualTo("PENDING");

    assertEquals(executionId, parsed.get().taskExecutionId());
    assertEquals("postmortem-cache", parsed.get().entryKey().value());
    assertEquals(Optional.empty(), parsed.get().category());
  }

  @Test
  void replaysReturnTheReceiptWithoutASecondEntryResult() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.distill(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(CommandExecution.replayed(receipt)));

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "distill-replay-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(executionId.value().toString(), "postmortem-cache", "DECISION"))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.entryId")
        .isEqualTo(null)
        .jsonPath("$.origin")
        .isEqualTo(null)
        .jsonPath("$.entryKey")
        .isEqualTo("postmortem-cache");
  }

  @Test
  void distillRequiresAnIdempotencyKeyAndAShapeWithinTheContract() {
    client
        .post()
        .uri(root())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(executionId.value().toString(), "postmortem-cache", null))
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "bad-category")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(executionId.value().toString(), "postmortem-cache", "MYSTERY"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request");
    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "bad-execution")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("not-a-uuid", "postmortem-cache", null))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void mapsDomainFailuresOntoTheSafeErrorFace() {
    when(service.distill(any(), any(), any()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new DomainValidationException(
                    "distillation.taskExecutionStatus", "must be COMPLETED")));
    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "not-completed")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(executionId.value().toString(), "postmortem-cache", null))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_value")
        .jsonPath("$.details.field")
        .isEqualTo("distillation.taskExecutionStatus");

    when(service.distill(any(), any(), any()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new KnowledgeEntryKeyConflictException(
                    new TeamScope(organizationId, teamId),
                    new KnowledgeEntryKey("postmortem-cache"))));
    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "key-conflict")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(executionId.value().toString(), "postmortem-cache", null))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("knowledge_entry_key_conflict");
  }
}
