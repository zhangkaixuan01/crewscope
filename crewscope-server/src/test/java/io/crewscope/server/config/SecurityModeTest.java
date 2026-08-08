package io.crewscope.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecurityModeTest {

  @Test
  void acceptsTheTwoExplicitSecurityProfilesCaseInsensitively() {
    assertEquals(SecurityMode.BOOTSTRAP, SecurityMode.from(" bootstrap "));
    assertEquals(SecurityMode.OIDC, SecurityMode.from("OIDC"));
  }

  @Test
  void rejectsUnknownOrBlankProfilesInsteadOfFallingBack() {
    assertThrows(IllegalArgumentException.class, () -> SecurityMode.from("legacy"));
    assertThrows(IllegalArgumentException.class, () -> SecurityMode.from(" "));
  }
}
