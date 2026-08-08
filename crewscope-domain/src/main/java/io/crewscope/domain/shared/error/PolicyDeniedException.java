package io.crewscope.domain.shared.error;

import java.util.Map;

/** Reports a server-evaluated authorization decision without exposing hidden policy facts. */
public final class PolicyDeniedException extends DomainException {

  public PolicyDeniedException(String action) {
    super(
        new DomainError(
            DomainErrorCode.POLICY_DENIED,
            "Current principal is not allowed to " + requireAction(action),
            Map.of("action", action.strip())));
  }

  private static String requireAction(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("action must not be blank");
    }
    return value.strip();
  }
}
