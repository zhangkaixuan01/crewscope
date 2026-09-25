package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.principal.PrincipalDirectoryCursor;
import io.crewscope.application.principal.PrincipalDirectoryEntry;
import io.crewscope.application.principal.PrincipalDirectoryFilterFingerprint;
import io.crewscope.application.principal.PrincipalDirectoryPage;
import io.crewscope.application.principal.PrincipalDirectoryQuery;
import io.crewscope.application.principal.PrincipalDirectoryQueryService;
import io.crewscope.application.principal.PrincipalKind;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Verifies the full-set directory response shape, its dual-mode paging contract and the privacy
 * boundary: parameters are parsed closed, a by-id lookup accepts nothing else, and a signed
 * continuation only replays on the screen that produced it.
 */
class PrincipalDirectoryControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final TeamId teamId = TeamId.generate();
  private final PrincipalId viewerId = PrincipalId.generate();
  private PrincipalDirectoryQueryService service;
  private PrincipalDirectoryCursorCodec cursorCodec;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(PrincipalDirectoryQueryService.class);
    cursorCodec = new PrincipalDirectoryCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", key())),
        Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC),
        Duration.ofMinutes(30));
    Principal actor = Principal.create(
        viewerId,
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "Owner",
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    TeamRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new TeamAccessContext(actor, true));
    client = WebTestClient.bindToController(
            new PrincipalDirectoryController(service, cursorCodec, resolver))
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void returnsUserAndAgentRowsWithRolesAndTheLegacyOffsetContract() {
    PrincipalId userId = PrincipalId.generate();
    PrincipalId agentId = PrincipalId.generate();
    when(service.search(any(), any())).thenReturn(new PrincipalDirectoryPage(
        List.of(
            new PrincipalDirectoryEntry(
                userId, PrincipalKind.USER, "Alice", PrincipalStatus.ACTIVE, List.of("TEAM_OWNER")),
            new PrincipalDirectoryEntry(
                agentId, PrincipalKind.AGENT, "Build Agent", PrincipalStatus.ACTIVE, List.of())),
        OptionalInt.of(20), Optional.empty()));

    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/teams/{teamId}/principals")
            .queryParam("q", "Al")
            .queryParam("offset", 0)
            .queryParam("limit", 50)
            .build(organizationId, teamId))
        .exchange()
        .expectStatus().isOk()
        .expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].principalId").isEqualTo(userId.toString())
        .jsonPath("$.items[0].kind").isEqualTo("USER")
        .jsonPath("$.items[0].displayName").isEqualTo("Alice")
        .jsonPath("$.items[0].roles[0]").isEqualTo("TEAM_OWNER")
        .jsonPath("$.items[1].kind").isEqualTo("AGENT")
        .jsonPath("$.nextOffset").isEqualTo(20)
        .jsonPath("$.nextCursor").doesNotExist()
        .jsonPath("$.items[0].email").doesNotExist()
        .jsonPath("$.items[0].token").doesNotExist();

    ArgumentCaptor<PrincipalDirectoryQuery> captor =
        ArgumentCaptor.forClass(PrincipalDirectoryQuery.class);
    verify(service).search(any(), captor.capture());
    assertEquals(Optional.of("Al"), captor.getValue().namePrefix());
    assertEquals(50, captor.getValue().limit());
  }

  @Test
  void forwardsPurposeTypesNamePrefixAndTheBoundedDefaultLimit() {
    when(service.search(any(), any())).thenReturn(emptyPage());

    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/teams/{teamId}/principals")
            .queryParam("namePrefix", "评审")
            .queryParam("types", "USER,AGENT")
            .queryParam("purpose", "AUDIT")
            .build(organizationId, teamId))
        .exchange()
        .expectStatus().isOk();

    ArgumentCaptor<PrincipalDirectoryQuery> captor =
        ArgumentCaptor.forClass(PrincipalDirectoryQuery.class);
    verify(service).search(any(), captor.capture());
    assertEquals(Optional.of("评审"), captor.getValue().namePrefix());
    assertEquals(Set.of(PrincipalKind.USER, PrincipalKind.AGENT), captor.getValue().types());
    assertEquals(io.crewscope.application.principal.PrincipalDirectoryPurpose.AUDIT,
        captor.getValue().purpose());
    assertEquals(20, captor.getValue().limit());
  }

  @Test
  void rejectsDisagreeingAliasesAndUnknownClosedSetValues() {
    request("q", "Al", "namePrefix", "Bob").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    request("purpose", "SPYING").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    request("types", "SERVICE").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    request("ids", "not-a-uuid").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    request("limit", "201").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
  }

  @Test
  void aByIdLookupAcceptsNoFilterOffsetOrContinuation() {
    request("ids", "00000000-0000-4000-8000-000000000001", "namePrefix", "A")
        .expectStatus().isBadRequest();
    request("ids", "00000000-0000-4000-8000-000000000001", "types", "USER")
        .expectStatus().isBadRequest();
    request("ids", "00000000-0000-4000-8000-000000000001", "offset", "0")
        .expectStatus().isBadRequest();
    request("ids", "00000000-0000-4000-8000-000000000001", "after", "anything")
        .expectStatus().isBadRequest();

    when(service.search(any(), any())).thenReturn(emptyPage());
    request("ids", "00000000-0000-4000-8000-000000000001").expectStatus().isOk();
    verify(service).search(any(), any());
  }

  @Test
  void offsetAndAfterAreMutuallyExclusive() {
    request("after", "token", "offset", "20").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    verify(service, never()).search(any(), any());
  }

  @Test
  void continuesFromTheSignedCursorAndReportsTheNextOneBack() {
    when(service.search(any(), any())).thenReturn(new PrincipalDirectoryPage(
        List.of(new PrincipalDirectoryEntry(
            PrincipalId.generate(), PrincipalKind.USER, "Bob", PrincipalStatus.ACTIVE, List.of())),
        OptionalInt.empty(),
        Optional.of(new PrincipalDirectoryCursor("bob", PrincipalId.generate()))));
    String token = cursorCodec.encode(scope(Optional.empty(), Set.of()),
        new PrincipalDirectoryCursor("alice", PrincipalId.generate()));

    String body = client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/teams/{teamId}/principals")
            .queryParam("after", token)
            .build(organizationId, teamId))
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .returnResult().getResponseBody();

    String nextCursor = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
    PrincipalDirectoryCursor position =
        cursorCodec.decode(nextCursor, scope(Optional.empty(), Set.of()));
    assertEquals("bob", position.sortKey());
  }

  @Test
  void rejectsCursorsFromAnotherFilterOrPurpose() {
    String token = cursorCodec.encode(scope(Optional.of("A"), Set.of()),
        new PrincipalDirectoryCursor("alice", PrincipalId.generate()));

    request("after", token, "namePrefix", "B").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");
    request("after", token, "purpose", "AUDIT").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");
    verify(service, never()).search(any(), any());
  }

  @Test
  void reportsAnExpiredCursorAsGone() {
    Clock stale = Clock.fixed(Instant.parse("2026-09-24T23:00:00Z"), ZoneOffset.UTC);
    PrincipalDirectoryCursorCodec staleCodec = new PrincipalDirectoryCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", key())), stale, Duration.ofMinutes(30));
    String token = staleCodec.encode(scope(Optional.empty(), Set.of()),
        new PrincipalDirectoryCursor("alice", PrincipalId.generate()));

    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/teams/{teamId}/principals")
            .queryParam("after", token)
            .build(organizationId, teamId))
        .exchange()
        .expectStatus().isEqualTo(410)
        .expectBody()
        .jsonPath("$.code").isEqualTo("cursor_expired");

    verify(service, never()).search(any(), any());
  }

  @Test
  void anAuditPurposeKeepsItsOwnPermissionBoundary() {
    when(service.search(any(), any())).thenThrow(
        new PolicyDeniedException("read Team Audit events"));

    request("purpose", "AUDIT").expectStatus().isForbidden()
        .expectBody().jsonPath("$.code").isEqualTo("policy_denied");
  }

  private WebTestClient.ResponseSpec request(String... parameters) {
    StringBuilder query = new StringBuilder();
    for (int index = 0; index < parameters.length; index += 2) {
      if (query.length() > 0) {
        query.append('&');
      }
      query.append(parameters[index]).append('=').append(parameters[index + 1]);
    }
    String suffix = query.length() == 0 ? "" : "?" + query;
    return client.get()
        .uri("/api/v1/organizations/" + organizationId + "/teams/" + teamId
            + "/principals" + suffix)
        .exchange();
  }

  /** The canonical fingerprint the controller binds into every scope, mirrored for the test. */
  private PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope scope(
      Optional<String> prefix, Set<PrincipalKind> kinds) {
    String canonical = "principal-directory-filter-v1\n"
        + "namePrefix=" + prefix.orElse("") + "\n"
        + "types=" + String.join(",", kinds.stream().map(Enum::name).sorted().toList()) + "\n";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new PrincipalDirectoryCursorCodec.PrincipalDirectoryCursorScope(
          organizationId, teamId, viewerId,
          io.crewscope.application.principal.PrincipalDirectoryPurpose.ASSIGNMENT,
          new PrincipalDirectoryFilterFingerprint(
              HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)))));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static PrincipalDirectoryPage emptyPage() {
    return new PrincipalDirectoryPage(List.of(), OptionalInt.empty(), Optional.empty());
  }

  private static String key() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (19 + index);
    }
    return java.util.Base64.getEncoder().encodeToString(value);
  }
}
