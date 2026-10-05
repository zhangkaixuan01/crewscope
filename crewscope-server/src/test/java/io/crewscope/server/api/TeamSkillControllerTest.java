package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.skill.CreateTeamSkillCommand;
import io.crewscope.application.skill.DistillTeamSkillCommand;
import io.crewscope.application.skill.SkillDistillationService;
import io.crewscope.application.skill.TeamSkillCommandService;
import io.crewscope.application.skill.TeamSkillFilter;
import io.crewscope.application.skill.TeamSkillPage;
import io.crewscope.application.skill.TeamSkillPageRequest;
import io.crewscope.application.skill.TeamSkillVersionPage;
import io.crewscope.application.skill.TeamSkillVersionPageRequest;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillDisclosureViolationException;
import io.crewscope.domain.skill.TeamSkillDisabledException;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillOrigin;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import io.crewscope.domain.skill.TeamSkillVersionUnchangedException;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamScope;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Proves the A03a Team Skill HTTP contract: 202 receipts, both ETag forms, the
 * unconditional-disclosure/switch/unchanged error face, and that there is no delete.
 */
class TeamSkillControllerTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");

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

  private TeamSkill draft;
  private TeamSkill published;
  private TeamSkillVersion version;
  private TeamSkillCommandService service;
  private SkillDistillationService distillation;
  private WebTestClient client;

  private String root() {
    return "/api/v1/organizations/" + organizationId + "/teams/" + teamId + "/skills";
  }

  private static String document(String name, String marker) {
    return """
        ---
        name: %s
        description: Marker %s of the drill.
        ---

        Body of %s.
        """
        .formatted(name, marker, marker);
  }

  @BeforeEach
  void setUp() {
    draft = TeamSkill.create(
        scope,
        TeamSkillKey.parse("deploy-runbook-v2"),
        document("deploy-runbook-v2", "d1"),
        Optional.empty(),
        actor.id(),
        NOW);
    io.crewscope.domain.skill.TeamSkillPublication publication = draft.publish(actor.id(), NOW);
    published = publication.skill();
    version = publication.version();
    service = mock(TeamSkillCommandService.class);
    distillation = mock(SkillDistillationService.class);
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, false));
    client =
        WebTestClient.bindToController(new TeamSkillController(service, distillation, resolver))
            .controllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @Test
  void createsASkillThroughTheAcceptedReceiptContract() {
    AtomicReference<CreateTeamSkillCommand> parsed = new AtomicReference<>();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(service.create(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              parsed.set(invocation.getArgument(2, CreateTeamSkillCommand.class));
              return CommandExecution.completed(draft, receipt);
            });

    client
        .post()
        .uri(root())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "create-skill-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            """
            {
              "skillKey":"deploy-runbook-v2",
              "content":"%s"
            }
            """
                .formatted(document("deploy-runbook-v2", "d1").replace("\n", "\\n").replace("\"", "\\\"")))
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

    assertEquals("deploy-runbook-v2", parsed.get().skillKey().value());
    assertEquals(document("deploy-runbook-v2", "d1"), parsed.get().content());
  }

  @Test
  void createValidatesTheIdempotencyKeySkillKeyAndContent() {
    client.post().uri(root()).contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"deploy-runbook-v2\",\"content\":\"x\"}")
        .exchange().expectStatus().isBadRequest();
    // Contract §4: key-format and reserved-name rejections are domain validation — 422
    // invalid_value, never a transport-level 400.
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "bad-key-case")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"Deploy_Runbook\",\"content\":\"x\"}")
        .exchange().expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.code").isEqualTo("invalid_value")
        .jsonPath("$.details.field").isEqualTo("teamSkill.skillKey");
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "reserved-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"java-spring-v1\",\"content\":\"x\"}")
        .exchange().expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.code").isEqualTo("invalid_value")
        .jsonPath("$.details.field").isEqualTo("teamSkill.skillKey");
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "blank-content")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"deploy-runbook-v2\",\"content\":\"\"}")
        .exchange().expectStatus().isBadRequest();
  }

  @Test
  void listsSkillsWithFiltersAndTheKeysetCursor() {
    AtomicReference<TeamSkillFilter> filterSeen = new AtomicReference<>();
    AtomicReference<TeamSkillPageRequest> pageSeen = new AtomicReference<>();
    when(service.teamListing(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              filterSeen.set(invocation.getArgument(3, TeamSkillFilter.class));
              pageSeen.set(invocation.getArgument(4, TeamSkillPageRequest.class));
              return new TeamSkillPage(
                  List.of(published), Optional.of(TeamSkillKey.parse("zzz-last")));
            });

    client
        .get()
        .uri(root() + "?status=PUBLISHED&after=alpha-drill&limit=25")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].id")
        .isEqualTo(published.id().value().toString())
        .jsonPath("$.items[0].skillKey")
        .isEqualTo("deploy-runbook-v2")
        .jsonPath("$.items[0].status")
        .isEqualTo("PUBLISHED")
        .jsonPath("$.items[0].effectiveRevision")
        .isEqualTo(1)
        .jsonPath("$.items[0].draft")
        .doesNotExist()
        .jsonPath("$.items[0].origin")
        .doesNotExist()
        .jsonPath("$.nextAfter")
        .isEqualTo("zzz-last");

    assertEquals(Set.of(io.crewscope.domain.skill.TeamSkillStatus.PUBLISHED),
        filterSeen.get().statuses());
    assertEquals("alpha-drill", pageSeen.get().afterSkillKey().orElseThrow().value());
    assertEquals(25, pageSeen.get().limit());

    client.get().uri(root() + "?status=MYSTERY").exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "?after=NOT_VALID_UPPER").exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "?limit=101").exchange().expectStatus().isBadRequest();
  }

  @Test
  void returnsTheHeadWithTheVersionEtagAndDerivedDraftFields() {
    when(service.skill(any(), any(), any(), any())).thenReturn(draft);

    client
        .get()
        .uri(root() + "/" + draft.id())
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals(ApiHeaders.ETAG, "\"0\"")
        .expectBody()
        .jsonPath("$.skillKey")
        .isEqualTo("deploy-runbook-v2")
        .jsonPath("$.status")
        .isEqualTo("DRAFT")
        .jsonPath("$.latestRevision")
        .isEqualTo(0)
        .jsonPath("$.draft.name")
        .isEqualTo("deploy-runbook-v2")
        .jsonPath("$.draft.description")
        .isEqualTo("Marker d1 of the drill.")
        .jsonPath("$.disableReason")
        .doesNotExist();

    when(service.skill(any(), any(), any(), any()))
        .thenThrow(new AggregateNotFoundException("TeamSkill", draft.id()));
    client.get().uri(root() + "/" + draft.id()).exchange().expectStatus().isNotFound();
  }

  @Test
  void updateDraftRequiresOneStrongIfMatchVersionAndPassesItThrough() {
    AtomicReference<Long> expected = new AtomicReference<>();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.updateDraft(any(), any(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              expected.set(invocation.getArgument(3, Long.class));
              return CommandExecution.completed(draft, receipt);
            });

    client
        .patch()
        .uri(root() + "/" + draft.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"" + document("deploy-runbook-v2", "d2")
            .replace("\n", "\\n").replace("\"", "\\\"") + "\"}")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.committedVersion")
        .isEqualTo(1);
    assertEquals(0L, expected.get());

    client
        .patch()
        .uri(root() + "/" + draft.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-no-if-match")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"x\"}")
        .exchange()
        .expectStatus()
        .isEqualTo(428);
    client
        .patch()
        .uri(root() + "/" + draft.id())
        .header(ApiHeaders.IDEMPOTENCY_KEY, "draft-weak-etag")
        .header(ApiHeaders.IF_MATCH, "W/\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"content\":\"x\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void publishDisableAndRollbackShareTheConcurrencyContract() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
    when(service.publish(any(), any(), any(), anyLong()))
        .thenReturn(CommandExecution.completed(published, receipt));
    AtomicReference<String> reasonSeen = new AtomicReference<>("unset");
    when(service.disable(any(), any(), any(), anyLong(), any()))
        .thenAnswer(
            invocation -> {
              reasonSeen.set(invocation.getArgument(4, String.class));
              return CommandExecution.completed(published, receipt);
            });
    AtomicReference<Long> targetSeen = new AtomicReference<>();
    when(service.rollback(any(), any(), any(), anyLong(), anyLong()))
        .thenAnswer(
            invocation -> {
              targetSeen.set(invocation.getArgument(4, Long.class));
              return CommandExecution.completed(published, receipt);
            });

    client
        .post()
        .uri(root() + "/" + draft.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "publish-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted();
    client
        .post()
        .uri(root() + "/" + draft.id() + "/disable")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "disable-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Superseded.\"}")
        .exchange()
        .expectStatus()
        .isAccepted();
    assertEquals("Superseded.", reasonSeen.get());
    // Disabling without a body is a silent retirement.
    client
        .post()
        .uri(root() + "/" + draft.id() + "/disable")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "disable-silent-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted();
    assertNull(reasonSeen.get());
    client
        .post()
        .uri(root() + "/" + draft.id() + "/rollback")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "rollback-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"toRevision\":1}")
        .exchange()
        .expectStatus()
        .isAccepted();
    assertEquals(1L, targetSeen.get());

    for (String action : List.of("publish", "disable", "rollback")) {
      // Rollback parses its required body before the header checks, so it needs one
      // to prove the 428 is really about the missing If-Match.
      client
          .post()
          .uri(root() + "/" + draft.id() + "/" + action)
          .header(ApiHeaders.IDEMPOTENCY_KEY, "action-" + action + "-no-if-match")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(action.equals("rollback") ? "{\"toRevision\":1}" : "{}")
          .exchange()
          .expectStatus()
          .isEqualTo(428);
    }
    client
        .post()
        .uri(root() + "/" + draft.id() + "/rollback")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "rollback-zero")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"toRevision\":0}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void immutableVersionsCarryTheContentHashEtag() {
    when(service.versionHistory(any(), any(), any(), any(), any()))
        .thenReturn(new TeamSkillVersionPage(
            List.of(version), Optional.of(new TeamSkillRevision(2))));
    when(service.version(any(), any(), any(), any(), any())).thenReturn(version);
    when(service.effectiveVersion(any(), any(), any(), any())).thenReturn(version);

    client
        .get()
        .uri(root() + "/" + draft.id() + "/versions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0].revision")
        .isEqualTo(1)
        .jsonPath("$.items[0].previousRevision")
        .doesNotExist()
        .jsonPath("$.items[0].contentHash")
        .isEqualTo(version.contentHash().value())
        .jsonPath("$.nextAfter")
        .isEqualTo(2);

    String expectedEtag = "\"" + version.contentHash().value() + "\"";
    client.get().uri(root() + "/" + draft.id() + "/versions/1")
        .exchange().expectStatus().isOk()
        .expectHeader().valueEquals(ApiHeaders.ETAG, expectedEtag);
    client.get().uri(root() + "/" + draft.id() + "/versions/0")
        .exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "/" + draft.id() + "/versions/999999999999999999999")
        .exchange().expectStatus().isBadRequest();
    client.get().uri(root() + "/" + draft.id() + "/effective-version")
        .exchange().expectStatus().isOk()
        .expectHeader().valueEquals(ApiHeaders.ETAG, expectedEtag);
  }

  @Test
  void surfacesOptimisticLockConflictsWithTheCurrentVersion() {
    when(service.publish(any(), any(), any(), anyLong()))
        .thenThrow(new OptimisticLockConflictException("TeamSkill", draft.id(), 0, 7));

    client
        .post()
        .uri(root() + "/" + draft.id() + "/publish")
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
  void mapsTheSkillErrorFaceOntoHttpStatuses() {
    doThrow(new PolicyDeniedException("manage this Team's skills"))
        .when(service).create(any(), any(), any());
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "denied-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"deploy-runbook-v2\",\"content\":\"x\"}")
        .exchange().expectStatus().isForbidden();

    doThrow(new TeamSkillKeyConflictException(scope, TeamSkillKey.parse("deploy-runbook-v2")))
        .when(service).create(any(), any(), any());
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "key-conflict-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"deploy-runbook-v2\",\"content\":\"x\"}")
        .exchange().expectStatus().isEqualTo(409)
        .expectBody().jsonPath("$.code").isEqualTo("skill_key_conflict");

    doThrow(new TeamSkillDisabledException())
        .when(service).create(any(), any(), any());
    client.post().uri(root()).header(ApiHeaders.IDEMPOTENCY_KEY, "switch-off-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"skillKey\":\"deploy-runbook-v2\",\"content\":\"x\"}")
        .exchange().expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.code").isEqualTo("skill_disabled");

    doThrow(new TeamSkillDisclosureViolationException("openai_api_key"))
        .when(service).publish(any(), any(), any(), anyLong());
    client.post().uri(root() + "/" + draft.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "disclosure-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange().expectStatus().isEqualTo(403)
        .expectBody().jsonPath("$.code").isEqualTo("skill_disclosure_denied");

    doThrow(new TeamSkillVersionUnchangedException(draft.id(), 1))
        .when(service).publish(any(), any(), any(), anyLong());
    client.post().uri(root() + "/" + draft.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "unchanged-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange().expectStatus().isEqualTo(422)
        .expectBody().jsonPath("$.code").isEqualTo("skill_version_unchanged");
  }

  @Test
  void replayedCommandsAreAcknowledgedWithTheReplayHeader() {
    when(service.publish(any(), any(), any(), anyLong()))
        .thenReturn(CommandExecution.replayed(
            new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID())));

    client
        .post()
        .uri(root() + "/" + draft.id() + "/publish")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "replay-http-1")
        .header(ApiHeaders.IF_MATCH, "\"0\"")
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true");
  }

  @Test
  void queriesNeverTouchTheWriteSwitch() {
    when(service.skill(any(), any(), any(), any())).thenReturn(draft);
    when(service.teamListing(any(), any(), any(), any(), any()))
        .thenReturn(new TeamSkillPage(List.of(draft), Optional.empty()));
    when(service.version(any(), any(), any(), any(), any())).thenReturn(version);

    client.get().uri(root()).exchange().expectStatus().isOk();
    client.get().uri(root() + "/" + draft.id()).exchange().expectStatus().isOk();
    client.get().uri(root() + "/" + draft.id() + "/versions").exchange().expectStatus().isOk();
    client.get().uri(root() + "/" + draft.id() + "/versions/1").exchange().expectStatus().isOk();
    client.get().uri(root() + "/" + draft.id() + "/effective-version")
        .exchange().expectStatus().isOk();
  }

  // ---------------------------------------------------------------- A03b distillations

  private final TaskExecutionId executionId = TaskExecutionId.generate();

  private String distillBody(String taskExecutionId, String skillKey) {
    return "{\"taskExecutionId\":\"" + taskExecutionId
        + "\",\"skillKey\":\"" + skillKey + "\"}";
  }

  @Test
  void distillsThroughTheAcceptedReceiptContract() {
    TeamSkill distilledDraft =
        TeamSkill.create(
            scope,
            TeamSkillKey.parse("restart-worker-pool"),
            document("restart-worker-pool", "d1"),
            Optional.of(new TeamSkillOrigin(executionId.value(), 2)),
            actor.id(),
            NOW);
    AtomicReference<DistillTeamSkillCommand> parsed = new AtomicReference<>();
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(distillation.distill(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              parsed.set(invocation.getArgument(2, DistillTeamSkillCommand.class));
              return CompletableFuture.completedFuture(
                  CommandExecution.completed(distilledDraft, receipt));
            });

    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "distill-http-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.skillId")
        .isEqualTo(distilledDraft.id().value().toString())
        .jsonPath("$.skillKey")
        .isEqualTo("restart-worker-pool")
        .jsonPath("$.status")
        .isEqualTo("DRAFT")
        .jsonPath("$.origin.taskExecutionId")
        .isEqualTo(executionId.value().toString())
        .jsonPath("$.origin.attempt")
        .isEqualTo(2);

    assertEquals(executionId, parsed.get().taskExecutionId());
    assertEquals("restart-worker-pool", parsed.get().skillKey().value());
  }

  @Test
  void distillReplaysReturnTheReceiptWithoutASecondSkillResult() {
    CommandReceipt receipt =
        new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID());
    when(distillation.distill(any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(CommandExecution.replayed(receipt)));

    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "distill-replay-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectHeader()
        .valueEquals(ApiHeaders.IDEMPOTENCY_REPLAYED, "true")
        .expectBody()
        .jsonPath("$.commandId")
        .isEqualTo(receipt.commandId().toString())
        .jsonPath("$.skillId")
        .isEqualTo(null)
        .jsonPath("$.skillKey")
        .isEqualTo("restart-worker-pool");
  }

  @Test
  void distillRequiresAnIdempotencyKeyAndAShapeWithinTheContract() {
    client
        .post()
        .uri(root() + "/distillations")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isBadRequest();
    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "bad-execution")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody("not-a-uuid", "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_request")
        .jsonPath("$.details.field")
        .isEqualTo("taskExecutionId");
    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "bad-key")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "NOT_A_KEY"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_value")
        .jsonPath("$.details.field")
        .isEqualTo("teamSkill.skillKey");
  }

  @Test
  void mapsDistillationFailuresOntoTheSafeErrorFace() {
    when(distillation.distill(any(), any(), any()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new PolicyDeniedException("distill from a Task they did not create")));
    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "not-creator")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isForbidden();

    when(distillation.distill(any(), any(), any()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new DomainValidationException(
                    "distillation.taskExecutionStatus", "must be COMPLETED")));
    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "not-completed")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isEqualTo(422)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("invalid_value")
        .jsonPath("$.details.field")
        .isEqualTo("distillation.taskExecutionStatus");

    when(distillation.distill(any(), any(), any()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new TeamSkillKeyConflictException(
                    scope, TeamSkillKey.parse("restart-worker-pool"))));
    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "key-conflict")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("skill_key_conflict");

    when(distillation.distill(any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new TeamSkillDisabledException()));
    client
        .post()
        .uri(root() + "/distillations")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "switch-off")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(distillBody(executionId.value().toString(), "restart-worker-pool"))
        .exchange()
        .expectStatus()
        .isEqualTo(422);
  }
}
