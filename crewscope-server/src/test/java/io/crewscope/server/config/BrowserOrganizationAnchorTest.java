package io.crewscope.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Browser Organization anchor: local precedence, OIDC authority and startup consistency. */
class BrowserOrganizationAnchorTest {

  private static final String ORGANIZATION_A =
      "0198a475-0831-7000-8000-000000000001";
  private static final String ORGANIZATION_B =
      "0198a475-0831-7000-8000-000000000002";

  @Test
  void localModeKeepsFollowingTheRegistrationKey() {
    BrowserOrganizationAnchor anchor =
        BrowserOrganizationAnchor.from(SecurityMode.LOCAL, ORGANIZATION_A, "");

    assertEquals(Optional.of(OrganizationId.from(ORGANIZATION_A)), anchor.browserOrganizationId());
    // A stray OIDC key must not leak into local-mode behaviour.
    assertEquals(Optional.empty(), anchor.oidcOrganizationId());
  }

  @Test
  void localModeWithoutARegistrationKeyStaysEmptySoEndpointsAnswerUnavailable() {
    BrowserOrganizationAnchor anchor = BrowserOrganizationAnchor.from(SecurityMode.LOCAL, "", "");

    assertEquals(Optional.empty(), anchor.browserOrganizationId());
  }

  @Test
  void oidcModeMakesTheOidcKeyAuthoritative() {
    BrowserOrganizationAnchor absent =
        BrowserOrganizationAnchor.from(SecurityMode.OIDC, "", ORGANIZATION_A);
    BrowserOrganizationAnchor matching =
        BrowserOrganizationAnchor.from(SecurityMode.OIDC, ORGANIZATION_A, ORGANIZATION_A);

    assertEquals(Optional.of(OrganizationId.from(ORGANIZATION_A)), absent.browserOrganizationId());
    assertEquals(matching.oidcOrganizationId(), matching.browserOrganizationId());
  }

  @Test
  void oidcModeWithoutItsKeyRefusesToStart() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> BrowserOrganizationAnchor.from(SecurityMode.OIDC, ORGANIZATION_A, ""));

    assertEquals(
        "crewscope.security.oidc.organization-id is required in oidc mode",
        failure.getMessage());
  }

  @Test
  void oidcModeRefusesAConflictingRegistrationKey() {
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> BrowserOrganizationAnchor.from(SecurityMode.OIDC, ORGANIZATION_B, ORGANIZATION_A));

    assertEquals(
        "crewscope.registration.organization-id must match crewscope.security.oidc"
            + ".organization-id in oidc mode",
        failure.getMessage());
  }
}
