package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.organization.OrganizationMemberDirectoryCursor;
import io.crewscope.application.organization.OrganizationMemberDirectoryEntry;
import io.crewscope.application.organization.OrganizationMemberDirectoryFilterFingerprint;
import io.crewscope.application.organization.OrganizationMemberDirectoryPage;
import io.crewscope.application.organization.OrganizationMemberDirectoryQuery;
import io.crewscope.application.organization.OrganizationMemberDirectoryQueryService;
import io.crewscope.application.organization.OrganizationAccessContext;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Verifies the M13b-A01 roster response shape, its dual-mode paging contract and the privacy
 * boundary: parameters are parsed closed, offset and a signed continuation never mix, and the
 * account's email never leaves the server.
 */
class OrganizationMemberDirectoryControllerTest {

  private final OrganizationId organizationId = OrganizationId.generate();
  private final PrincipalId viewerId = PrincipalId.generate();
  private OrganizationMemberDirectoryQueryService service;
  private OrganizationMemberCursorCodec cursorCodec;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    service = mock(OrganizationMemberDirectoryQueryService.class);
    cursorCodec = new OrganizationMemberCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", key())),
        Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC),
        Duration.ofMinutes(30));
    Principal actor = Principal.create(
        viewerId,
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "Auditor",
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        UtcTimestamp.parse("2026-08-08T03:00:00Z"));
    OrganizationRequestIdentityResolver resolver =
        (authentication, organization, correlationId) ->
            Mono.just(new OrganizationAccessContext(actor));
    client = WebTestClient.bindToController(
            new OrganizationMemberDirectoryController(service, cursorCodec, resolver))
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void returnsMemberRowsWithAccountIdentityRolesAndTheLegacyOffsetContract() {
    UserAccountId accountId = UserAccountId.generate();
    PrincipalId principalId = PrincipalId.generate();
    when(service.search(any(), any())).thenReturn(new OrganizationMemberDirectoryPage(
        List.of(new OrganizationMemberDirectoryEntry(
            accountId, principalId, "Alice", "alice", AccountStatus.ACTIVE,
            List.of("ORG_AUDITOR"))),
        OptionalInt.of(20), Optional.empty()));

    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/members")
            .queryParam("namePrefix", "Al")
            .queryParam("offset", 0)
            .queryParam("limit", 50)
            .build(organizationId))
        .exchange()
        .expectStatus().isOk()
        .expectHeader().valueEquals("Cache-Control", "no-store")
        .expectBody()
        .jsonPath("$.items[0].accountId").isEqualTo(accountId.toString())
        .jsonPath("$.items[0].principalId").isEqualTo(principalId.toString())
        .jsonPath("$.items[0].displayName").isEqualTo("Alice")
        .jsonPath("$.items[0].username").isEqualTo("alice")
        .jsonPath("$.items[0].status").isEqualTo("ACTIVE")
        .jsonPath("$.items[0].orgRoles[0]").isEqualTo("ORG_AUDITOR")
        .jsonPath("$.nextOffset").isEqualTo(20)
        .jsonPath("$.nextCursor").doesNotExist()
        .jsonPath("$.items[0].email").doesNotExist()
        .jsonPath("$.items[0].emailNormalized").doesNotExist();

    ArgumentCaptor<OrganizationMemberDirectoryQuery> captor =
        ArgumentCaptor.forClass(OrganizationMemberDirectoryQuery.class);
    verify(service).search(any(), captor.capture());
    assertEquals(Optional.of("Al"), captor.getValue().namePrefix());
    assertEquals(50, captor.getValue().limit());
  }

  @Test
  void rejectsUnknownParameterShapesAndNegativeOffsets() {
    request("limit", "201").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    request("offset", "-1").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    request("namePrefix", "x".repeat(101)).expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    client.get()
        .uri("/api/v1/organizations/not-a-uuid/members")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    verify(service, never()).search(any(), any());
  }

  @Test
  void offsetAndAfterAreMutuallyExclusive() {
    request("after", "token", "offset", "20").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    verify(service, never()).search(any(), any());
  }

  @Test
  void continuesFromTheSignedCursorAndReportsTheNextOneBack() {
    when(service.search(any(), any())).thenReturn(new OrganizationMemberDirectoryPage(
        List.of(new OrganizationMemberDirectoryEntry(
            UserAccountId.generate(), PrincipalId.generate(), "Bob", "bob",
            AccountStatus.LOCKED, List.of())),
        OptionalInt.empty(),
        Optional.of(new OrganizationMemberDirectoryCursor("bob", PrincipalId.generate()))));
    String token = cursorCodec.encode(cursor(scope(Optional.empty()),
        new OrganizationMemberDirectoryCursor("alice", PrincipalId.generate())));

    String body = client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/members")
            .queryParam("after", token)
            .build(organizationId))
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .returnResult().getResponseBody();

    String nextCursor = com.jayway.jsonpath.JsonPath.read(body, "$.nextCursor");
    OrganizationMemberDirectoryCursor position =
        cursorCodec.decode(nextCursor, scope(Optional.empty()));
    assertEquals("bob", position.sortKey());
  }

  @Test
  void rejectsCursorsFromAnotherFilter() {
    String token = cursorCodec.encode(cursor(scope(Optional.of("A")),
        new OrganizationMemberDirectoryCursor("alice", PrincipalId.generate())));

    request("after", token, "namePrefix", "B").expectStatus().isBadRequest()
        .expectBody().jsonPath("$.code").isEqualTo("invalid_cursor");
    verify(service, never()).search(any(), any());
  }

  @Test
  void reportsAnExpiredCursorAsGone() {
    Clock stale = Clock.fixed(Instant.parse("2026-10-18T23:00:00Z"), ZoneOffset.UTC);
    OrganizationMemberCursorCodec staleCodec = new OrganizationMemberCursorCodec(
        new TeamActivityCursorKeyRing("k1", Map.of("k1", key())), stale, Duration.ofMinutes(30));
    String token = staleCodec.encode(cursor(scope(Optional.empty()),
        new OrganizationMemberDirectoryCursor("alice", PrincipalId.generate())));

    client.get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/v1/organizations/{organizationId}/members")
            .queryParam("after", token)
            .build(organizationId))
        .exchange()
        .expectStatus().isEqualTo(410)
        .expectBody()
        .jsonPath("$.code").isEqualTo("cursor_expired");

    verify(service, never()).search(any(), any());
  }

  @Test
  void theAuditReadCapabilityKeepsItsOwnPermissionBoundary() {
    when(service.search(any(), any())).thenThrow(
        new PolicyDeniedException("read organization audit"));

    request().expectStatus().isForbidden()
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
        .uri("/api/v1/organizations/" + organizationId + "/members" + suffix)
        .exchange();
  }

  /** The canonical fingerprint the controller binds into every scope, mirrored for the test. */
  private OrganizationMemberCursorCodec.OrganizationMemberCursorScope scope(
      Optional<String> prefix) {
    String canonical = "organization-member-directory-filter-v1\n"
        + "namePrefix=" + prefix.orElse("") + "\n";
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new OrganizationMemberCursorCodec.OrganizationMemberCursorScope(
          organizationId, viewerId,
          new OrganizationMemberDirectoryFilterFingerprint(
              HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)))));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static OrganizationMemberCursorCodec.OrganizationMemberCursor cursor(
      OrganizationMemberCursorCodec.OrganizationMemberCursorScope scope,
      OrganizationMemberDirectoryCursor position) {
    return new OrganizationMemberCursorCodec.OrganizationMemberCursor(scope, position);
  }

  private static String key() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (19 + index);
    }
    return java.util.Base64.getEncoder().encodeToString(value);
  }
}
