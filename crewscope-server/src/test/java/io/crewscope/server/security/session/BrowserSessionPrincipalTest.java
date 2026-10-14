package io.crewscope.server.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Session-principal provider normalization and legacy-shape compatibility (M12-I01). */
class BrowserSessionPrincipalTest {

  @Test
  void normalizesAbsentProvidersToLocalForLegacyRedisSessions() {
    UUID accountId = UUID.randomUUID();

    assertEquals("local", new BrowserSessionPrincipal(accountId, 7).identityProvider());
    assertEquals("local", new BrowserSessionPrincipal(accountId, 7, null).identityProvider());
    assertEquals("local", new BrowserSessionPrincipal(accountId, 7, "  ").identityProvider());
  }

  @Test
  void retainsTheFederatedProviderThatEstablishedTheSession() {
    UUID accountId = UUID.randomUUID();

    BrowserSessionPrincipal federated =
        new BrowserSessionPrincipal(accountId, 7, "oidc/company");

    assertEquals("oidc/company", federated.identityProvider());
    // The principal name stays the account coordinate every registry lookup relies on.
    assertEquals(accountId.toString(), federated.getName());
  }

  @Test
  void keepsValidatingTheAccountCoordinates() {
    UUID accountId = UUID.randomUUID();

    assertThrows(
        NullPointerException.class,
        () -> new BrowserSessionPrincipal(null, 7, "local"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrowserSessionPrincipal(accountId, 0, "local"));
  }
}
