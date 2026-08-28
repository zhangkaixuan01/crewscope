package io.crewscope.server.security;

import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import java.util.List;
import java.util.Objects;

/** Converts the current persisted PlatformRole into Spring Security authority names. */
public final class PlatformRoleAuthorities {

  public static final String USER = "ROLE_USER";
  public static final String OPERATOR = "ROLE_OPERATOR";

  private PlatformRoleAuthorities() {}

  public static List<String> namesFor(UserAccount account) {
    UserAccount required = Objects.requireNonNull(account, "account");
    if (!required.canAuthenticate()) {
      throw new PolicyDeniedException("act with this account");
    }
    return required.platformRole() == PlatformRole.OPERATOR
        ? List.of(USER, OPERATOR)
        : List.of(USER);
  }

  public static boolean isOperator(UserAccount account) {
    return namesFor(account).contains(OPERATOR);
  }
}
