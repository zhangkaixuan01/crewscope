package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workdesk.WorkDeskItem;
import io.crewscope.application.workdesk.WorkDeskQueryService;
import io.crewscope.application.workdesk.WorkDeskSection;
import io.crewscope.application.workdesk.WorkDeskSectionPosition;
import io.crewscope.application.workdesk.WorkDeskSummary;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Proves WorkDesk parsing, server-resolved identity, the section-limit contract and the signed
 * nextCursor lifecycle: a section's continuation decodes only on the member, section and filter
 * that issued it.
 */
class WorkDeskControllerTest {
  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final WorkProjectId projectId = WorkProjectId.generate();
  private final PrincipalId viewerId = PrincipalId.generate();
  private WorkDeskQueryService service;
  private WorkDeskSectionCursorCodec cursorCodec;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(WorkDeskQueryService.class);
    cursorCodec = new WorkDeskSectionCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", key())),
        Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC),
        Duration.ofMinutes(30));
    Principal actor = mock(Principal.class);
    when(actor.id()).thenReturn(viewerId);
    TeamAccessContext access = new TeamAccessContext(actor, false);
    TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
        Mono.just(access);
    client = WebTestClient.bindToController(new WorkDeskController(service, cursorCodec, resolver))
        .controllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void returnsTheDerivedSummaryWithoutAcceptingAMemberId() {
    WorkDeskSummary summary = new WorkDeskSummary(
        organizationId.toString(), teamId.toString(), Optional.of(projectId.toString()),
        Instant.parse("2026-09-13T00:00:00Z"), List.of());
    when(service.summarize(any(), eq(organizationId), eq(teamId), eq(Optional.of(projectId)), any(), eq(true), eq(20)))
        .thenReturn(summary);

    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?projectId={projectId}&onlyNeedsAction=true",
            organizationId, teamId, projectId)
        .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody().jsonPath("$.organizationId").isEqualTo(organizationId.toString())
        .jsonPath("$.projectId").isEqualTo(projectId.toString());
    verify(service).summarize(any(), eq(organizationId), eq(teamId), eq(Optional.of(projectId)), any(), eq(true), eq(20));
  }

  @Test
  void handsEachTruncatedSectionItsOwnSignedContinuation() {
    WorkDeskSectionPosition position =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T08:00:00Z"), UUID.randomUUID());
    WorkDeskItem item = new WorkDeskItem(
        "WORK_ITEM", UUID.randomUUID().toString(), Optional.empty(), Optional.of("Row"),
        "IN_PROGRESS", Instant.parse("2026-09-13T09:00:00Z"), Optional.of("OWNER"), true, "HIGH",
        Optional.of(40), List.of(), Optional.empty(), "/work?workItem=x");
    when(service.summarize(any(), eq(organizationId), eq(teamId), any(), any(), eq(false), eq(25)))
        .thenReturn(new WorkDeskSummary(
            organizationId.toString(), teamId.toString(), Optional.empty(),
            Instant.parse("2026-09-13T00:00:00Z"),
            List.of(new WorkDeskSection("WORK_ITEM", "我的工作项", 4, 30, true,
                List.of(item), Optional.of(position)))));

    String body = client.get()
        .uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?limit=25",
            organizationId, teamId)
        .exchange().expectStatus().isOk()
        .expectBody(String.class)
        .returnResult().getResponseBody();

    assertEquals(true, com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].truncated"));
    assertEquals(30, (Integer) com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].total"));
    assertEquals(true, com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].items[0].needsAction"));
    org.junit.jupiter.api.Assertions.assertNull(
        com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].items[0].workItemId"));
    org.junit.jupiter.api.Assertions.assertNull(
        com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].items[0].rowSummary"));
    org.junit.jupiter.api.Assertions.assertNull(
        com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].items[0].waitingOn"));
    String token = com.jayway.jsonpath.JsonPath.read(body, "$.sections[0].nextCursor");
    WorkDeskSectionPosition decoded = cursorCodec.decode(token, scope("WORK_ITEM")).position();
    assertEquals(position, decoded);
  }

  @Test
  void continuesOneSectionFromItsSignedCursor() {
    WorkDeskSectionPosition position =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T08:00:00Z"), UUID.randomUUID());
    WorkDeskSectionPosition next =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T07:00:00Z"), UUID.randomUUID());
    when(service.summarizeSection(any(), eq(organizationId), eq(teamId), any(), any(), eq(false),
            eq(25), eq("WORK_ITEM"), eq(position)))
        .thenReturn(new WorkDeskSection("WORK_ITEM", "我的工作项", 4, 30, true, List.of(), Optional.of(next)));
    String after = cursorCodec.encode(
        new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(scope("WORK_ITEM"), position)).toString();

    String body = client.get()
        .uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk/sections/WORK_ITEM?after={after}&limit=25",
            organizationId, teamId, after)
        .exchange().expectStatus().isOk()
        .expectBody(String.class)
        .returnResult().getResponseBody();

    assertEquals("WORK_ITEM", com.jayway.jsonpath.JsonPath.read(body, "$.key"));
    assertEquals(30, (Integer) com.jayway.jsonpath.JsonPath.read(body, "$.total"));
    String token = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
    assertEquals(next, cursorCodec.decode(token, scope("WORK_ITEM")).position());
    verify(service).summarizeSection(any(), eq(organizationId), eq(teamId), any(), any(), eq(false),
        eq(25), eq("WORK_ITEM"), eq(position));
  }

  @Test
  void rejectsUnknownSectionsMissingAfterAndCursorsFromAnotherQuery() {
    WorkDeskSectionPosition position =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T08:00:00Z"), UUID.randomUUID());
    String after = cursorCodec.encode(
        new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(scope("WORK_ITEM"), position)).toString();

    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk/sections/NOT_A_SECTION?after={after}",
            organizationId, teamId, after)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk/sections/WORK_ITEM",
            organizationId, teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    // A cursor from another section is invalid, not a continuation of this one.
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk/sections/REVIEW?after={after}",
            organizationId, teamId, after)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?limit=101",
            organizationId, teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
  }

  @Test
  void reportsAnExpiredSectionCursorAsGone() {
    WorkDeskSectionPosition position =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T08:00:00Z"), UUID.randomUUID());
    // Signed at 23:00 while the controller's clock reads 00:00: past the thirty-minute maximum age,
    // so decode itself reports the expiry before any service call happens.
    String after = new WorkDeskSectionCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", key())),
        Clock.fixed(Instant.parse("2026-09-12T23:00:00Z"), ZoneOffset.UTC),
        Duration.ofMinutes(30)).encode(
        new WorkDeskSectionCursorCodec.WorkDeskSectionCursor(scope("WORK_ITEM"), position));

    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk/sections/WORK_ITEM?after={after}",
            organizationId, teamId, after)
        .exchange().expectStatus().isEqualTo(410).expectBody().jsonPath("$.code").isEqualTo("cursor_expired");
    verify(service, org.mockito.Mockito.never()).summarizeSection(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
  }

  @Test
  void rejectsMalformedScopeProjectAndRoleBeforeCallingTheService() {
    client.get().uri("/api/v1/organizations/not-an-id/teams/{teamId}/work-desk", teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?projectId=bad", organizationId, teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.get().uri("/api/v1/organizations/{organizationId}/teams/{teamId}/work-desk?responsibilityRole=unknown", organizationId, teamId)
        .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_request");
  }

  private WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope scope(String sectionKey) {
    return new WorkDeskSectionCursorCodec.WorkDeskSectionCursorScope(
        organizationId, teamId, viewerId, sectionKey,
        WorkDeskApiSupport.fingerprint(Optional.empty(), Optional.empty(), false));
  }

  private static String key() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (11 + index);
    }
    return Base64.getEncoder().encodeToString(value);
  }
}
