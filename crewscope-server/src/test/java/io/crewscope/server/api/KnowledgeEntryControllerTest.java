package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.knowledge.CreateKnowledgeEntryCommand;
import io.crewscope.application.knowledge.KnowledgeCommandService;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.knowledge.UpdateKnowledgeDraftCommand;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeDisclosureViolationException;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryOrigin;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.knowledge.KnowledgeVersionContentConflictException;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamScope;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Proves the A02a knowledge HTTP contract: receipts, both ETag forms and the error face. */
class KnowledgeEntryControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-01T09:00:00Z");

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
  private final TeamScope scope = new TeamScope(organizationId, teamId);
  private final TaskExecutionId executionId = TaskExecutionId.generate();

  private KnowledgeEntry entry;
  private KnowledgeEntryVersion version;
  private KnowledgeCommandService service;
  private WebTestClient client;

  private String root() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/knowledge/entries";
  }

  @BeforeEach
  void setUp() {
    entry =
        KnowledgeEntry.create(
            scope,
            new KnowledgeEntryKey("oncall-runbook"),
            KnowledgeCategory.RUNBOOK,
            "On-call runbook",
            "Step one",
            actor.id(),
            NOW);
    version =
        KnowledgeEntryVersion.create(
            entry.id(),
            scope,
            new KnowledgeEntryRevision(1),
            Optional.empty(),
            "On-call runbook",
            "Step one",
            actor.id(),
            NOW);
    service = mock(KnowledgeCommandService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new KnowledgeEntryController(service, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void createsAnEntryThroughTheAcceptedReceiptContract() {
    AtomicReference<CreateKnowledgeEntryCommand> parsed = new AtomicReference<>();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.create(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              parsed.set(invocation.getArgument(2, CreateKnowledgeEntryCommand.class));
              return CommandExecution.completed(entry, receipt);
            });

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "create-knowledge-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "entryKey":"oncall-runbook",
              "category":"RUNBOOK",
              "title":"On-call runbook",
              "content":"Step one"
            }
            """)
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.domainEventId")
        .isEqualTo(receipt.domainEventId().toString())
        .jsonPath("$.committedVersion")
        .isEqualTo(0);

    assertEquals("oncall-runbook", parsed.get().entryKey().value());
    assertEquals(KnowledgeCategory.RUNBOOK, parsed.get().category());
    assertEquals("On-call runbook", parsed.get().title());
    assertEquals("Step one", parsed.get().content());
  }

  @Test
  void createRequiresAnIdempotencyKeyAndAKnownCategory() {
    client.post().uri(root()).contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"entryKey\":\"k\",\"category\":\"RUNBOOK\",\"title\":\"T\",\"content\":\"C\"}")
        .exchange().expectStatus().isBadRequest();
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "bad-category")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"entryKey\":\"k\",\"category\":\"MYSTERY\",\"title\":\"T\",\"content\":\"C\"}")
        .exchange().expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "long-title")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            "{\"entryKey\":\"k\",\"category\":\"RUNBOOK\",\"title\":\""
                + "x".repeat(201)
                + "\",\"content\":\"C\"}")
        .exchange().expectStatus().isBadRequest();
  }

  @Test
  void listsEntriesWithFiltersAndTheKeysetCursor() {
    AtomicReference<KnowledgeEntryFilter> filterSeen = new AtomicReference<>();
    AtomicReference<KnowledgeEntryPageRequest> pageSeen = new AtomicReference<>();
    KnowledgeEntry published =
        KnowledgeEntry.reconstitute(
            entry.id(),
            scope,
            entry.entryKey(),
            KnowledgeCategory.RUNBOOK,
            Optional.of(new KnowledgeEntryOrigin(executionId.value(), 2)),
            KnowledgeEntryStatus.PUBLISHED,
            Optional.of(new KnowledgeEntryRevision(1)),
            1,
            Optional.empty(),
            2,
            entry.audit());
    when(service.teamListing(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              filterSeen.set(invocation.getArgument(3, KnowledgeEntryFilter.class));
              pageSeen.set(invocation.getArgument(4, KnowledgeEntryPageRequest.class));
              return new KnowledgeEntryPage(
                  List.of(published), Optional.of(new KnowledgeEntryKey("zzz-last")));
            });

    client
        .get()
        .uri(
            root()
                + "?status=PUBLISHED&category=RUNBOOK&after=alpha&limit=25")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].id")
        .isEqualTo(entry.id().value().toString())
        .jsonPath("$.items[0].indexStatus")
        .isEqualTo("PENDING")
        .jsonPath("$.items[0].effectiveRevision")
        .isEqualTo(1)
        .jsonPath("$.items[0].origin.taskExecutionId")
        .isEqualTo(executionId.value().toString())
        .jsonPath("$.items[0].origin.attempt")
        .isEqualTo(2)
        .jsonPath("$.nextAfter")
        .isEqualTo("zzz-last");

    assertEquals(KnowledgeCategory.RUNBOOK, filterSeen.get().category().orElseThrow());
    assertEquals(Set.of(KnowledgeEntryStatus.PUBLISHED), filterSeen.get().statuses());
    assertEquals("alpha", pageSeen.get().afterEntryKey().orElseThrow().value());
    assertEquals(25, pageSeen.get().limit());

    client.get().uri(root() + "?after=NOT_VALID_UPPER").exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "?category=MYSTERY").exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "?limit=101").exchange().expectStatus().isBadRequest();
  }

  @Test
  void returnsTheHeadWithTheVersionEtagAndPendingIndexStatus() {
    when(service.entry(any(), any(), any(), any())).thenReturn(entry);

    client
        .get()
        .uri(root() + "/" + entry.id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(ApiHeaders.ETAG, "\"0\"")
        .expectBody()
        .jsonPath("$.entryKey")
        .isEqualTo("oncall-runbook")
        .jsonPath("$.category")
        .isEqualTo("RUNBOOK")
        .jsonPath("$.status")
        .isEqualTo("DRAFT")
        .jsonPath("$.indexStatus")
        .isEqualTo("PENDING")
        .jsonPath("$.draft.title")
        .isEqualTo("On-call runbook")
        .jsonPath("$.latestRevision")
        .isEqualTo(0);

    when(service.entry(any(), any(), any(), any()))
        .thenThrow(new AggregateNotFoundException("KnowledgeEntry", entry.id()));
    client.get().uri(root() + "/" + entry.id()).exchange().expectStatus().isNotFound();
  }

  @Test
  void updateDraftRequiresOneStrongIfMatchVersionAndPassesItThrough() {
    AtomicReference<Long> expected = new AtomicReference<>();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 4, UUID.randomUUID());
    when(service.updateDraft(any(), any(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              expected.set(invocation.getArgument(3, Long.class));
              return CommandExecution.completed(entry, receipt);
            });

    client
        .patch()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-http-1")
        .header(ApiHeaders.IF_MATCH, "\"3\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Revised\",\"content\":\"Step zero\",\"category\":\"GUIDE\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(4);
    assertEquals(3L, expected.get());

    client
        .patch()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-no-if-match")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Revised\",\"content\":\"Step zero\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(428);
    client
        .patch()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-weak-etag")
        .header(ApiHeaders.IF_MATCH, "W/\"3\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Revised\",\"content\":\"Step zero\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void publishRetireAndDeleteShareTheConcurrencyContract() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.publish(any(), any(), any(), anyLong()))
        .thenReturn(CommandExecution.completed(entry, receipt));
    when(service.retire(any(), any(), any(), anyLong()))
        .thenReturn(CommandExecution.completed(entry, receipt));
    when(service.delete(any(), any(), any(), anyLong()))
        .thenReturn(CommandExecution.completed(entry, receipt));
    for (String action : List.of("publish", "retire")) {
      client
          .post()
          .uri(root() + "/" + entry.id() + "/" + action)
          .header(ApiHeaders.IDEMPOTENCY_KEY, "action-" + action)
          .header(ApiHeaders.IF_MATCH, "\"0\"")
          .exchange()
          .expectStatus()
          .isAccepted();
      client
          .post()
          .uri(root() + "/" + entry.id() + "/" + action)
          .header(ApiHeaders.IDEMPOTENCY_KEY, "action-" + action + "-no-if-match")
          .exchange()
          .expectStatus()
          .isEqualTo(428);
    }
    client
        .delete()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "delete-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted();
    client
        .delete()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void immutableVersionsCarryTheContentHashEtag() {
    when(service.versionHistory(any(), any(), any(), any(), any()))
        .thenReturn(new KnowledgeEntryVersionPage(
            List.of(version), Optional.of(new KnowledgeEntryRevision(2))));
    when(service.version(any(), any(), any(), any(), any())).thenReturn(version);
    when(service.effectiveVersion(any(), any(), any(), any())).thenReturn(version);

    client
        .get()
        .uri(root() + "/" + entry.id() + "/versions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].revision")
        .isEqualTo(1)
        .jsonPath("$.items[0].contentHash")
        .isEqualTo(version.contentHash().value())
        .jsonPath("$.items[0].indexStatus")
        .isEqualTo("PENDING")
        .jsonPath("$.nextAfter")
        .isEqualTo(2);

    String expectedEtag = "\"" + version.contentHash().value() + "\"";
    client.get().uri(root() + "/" + entry.id() + "/versions/1")
        .exchange().expectStatus().isOk()
        .expectHeader().valueEquals(ApiHeaders.ETAG, expectedEtag);
    client.get().uri(root() + "/" + entry.id() + "/versions/0")
        .exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "/" + entry.id() + "/effective-version")
        .exchange().expectStatus().isOk()
        .expectHeader().valueEquals(ApiHeaders.ETAG, expectedEtag);
  }

  @Test
  void surfacesDomainConflictsWithTheCurrentVersion() {
    when(service.publish(any(), any(), any(), anyLong()))
        .thenThrow(
            new OptimisticLockConflictException("KnowledgeEntry", entry.id(), 0, 7));

    client
        .post()
        .uri(root() + "/" + entry.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "conflict-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("optimistic_lock_conflict")
        .jsonPath("$.currentVersion")
        .isEqualTo(7);
  }

  @Test
  void mapsDisclosureDenialOntoTheSafeErrorFace() {
    when(service.publish(any(), any(), any(), anyLong()))
        .thenThrow(new KnowledgeDisclosureViolationException("openai_api_key"));

    client
        .post()
        .uri(root() + "/" + entry.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "disclosure-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isEqualTo(403)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("knowledge_disclosure_denied");
  }

  @Test
  void mapsDomainFailuresOntoTheErrorFace() {
    // doThrow style: re-stubbing a method that already throws would fire the old
    // stub while evaluating when(...), leaking the exception into the test itself.
    doThrow(new PolicyDeniedException("manage this Team's knowledge"))
        .when(service).create(any(), any(), any());
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "denied-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"entryKey\":\"k\",\"category\":\"RUNBOOK\",\"title\":\"T\",\"content\":\"C\"}")
        .exchange().expectStatus().isForbidden();

    doThrow(new KnowledgeEntryKeyConflictException(scope, entry.entryKey()))
        .when(service).create(any(), any(), any());
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "key-conflict-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"entryKey\":\"oncall-runbook\",\"category\":\"RUNBOOK\",\"title\":\"T\",\"content\":\"C\"}")
        .exchange().expectStatus().isEqualTo(409)
        .expectBody().jsonPath("$.code").isEqualTo("knowledge_entry_key_conflict");

    doThrow(new KnowledgeVersionContentConflictException(
            entry.id(), new KnowledgeEntryRevision(2), version.contentHash()))
        .when(service).publish(any(), any(), any(), anyLong());
    client.post().uri(root() + "/" + entry.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "content-conflict-http-1")
        .header(ApiHeaders.IF_MATCH, "\"1\"")
        .exchange().expectStatus().isEqualTo(409)
        .expectBody().jsonPath("$.code").isEqualTo("knowledge_version_content_conflict");
  }

  @Test
  void replayedCommandsAreAcknowledgedWithTheReplayHeader() {
    when(service.publish(any(), any(), any(), anyLong()))
        .thenReturn(CommandExecution.replayed(
            new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID())));

    client
        .post()
        .uri(root() + "/" + entry.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "replay-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
  }

  @Test
  void updateDraftCategoryIsOptionalAndValidatedWhenPresent() {
    AtomicReference<UpdateKnowledgeDraftCommand> parsed = new AtomicReference<>();
    when(service.updateDraft(any(), any(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              parsed.set(invocation.getArgument(4, UpdateKnowledgeDraftCommand.class));
              return CommandExecution.completed(
                  entry, new CommandReceipt(UUID.randomUUID(), entry.id().value(), 1, UUID.randomUUID()));
            });

    client
        .patch()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-category-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Revised\",\"content\":\"Step zero\"}")
        .exchange()
        .expectStatus()
        .isAccepted();
    assertFalse(parsed.get().category().isPresent());

    client
        .patch()
        .uri(root() + "/" + entry.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-bad-category")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Revised\",\"content\":\"Step zero\",\"category\":\"MYSTERY\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
