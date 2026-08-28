package io.crewscope.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import org.junit.jupiter.api.Test;

class PlatformRoleAuthoritiesTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T03:00:00Z");

  @Test
  void selfRegisteredAccountReceivesOnlyUserAuthority() {
    UserAccount account =
        UserAccount.register(
            UserAccountId.generate(), "member", "member@example.test", "Member", NOW);

    assertEquals(
        java.util.List.of(PlatformRoleAuthorities.USER),
        PlatformRoleAuthorities.namesFor(account));
    assertFalse(PlatformRoleAuthorities.isOperator(account));
  }

  @Test
  void bootstrapOperatorReceivesUserAndOperatorAuthorities() {
    UserAccount account =
        UserAccount.bootstrapOperator(
            UserAccountId.generate(), "operator", "operator@example.test", "Operator", NOW);

    assertEquals(
        java.util.List.of(
            PlatformRoleAuthorities.USER, PlatformRoleAuthorities.OPERATOR),
        PlatformRoleAuthorities.namesFor(account));
    assertTrue(PlatformRoleAuthorities.isOperator(account));
  }

  @Test
  void disabledOperatorCannotRetainAuthorities() {
    UserAccount account =
        UserAccount.bootstrapOperator(
                UserAccountId.generate(), "operator", "operator@example.test", "Operator", NOW)
            .transitionTo(
                AccountStatus.DISABLED, UtcTimestamp.parse("2026-08-08T03:01:00Z"));

    assertThrows(PolicyDeniedException.class, () -> PlatformRoleAuthorities.namesFor(account));
  }
}
