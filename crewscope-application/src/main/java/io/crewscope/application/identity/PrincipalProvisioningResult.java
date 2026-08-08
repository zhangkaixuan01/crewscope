package io.crewscope.application.identity;

import io.crewscope.domain.identity.Principal;
import java.util.Objects;

/** Result of resolving one external identity at the durable uniqueness boundary. */
public record PrincipalProvisioningResult(Principal principal, boolean created) {

  public PrincipalProvisioningResult {
    principal = Objects.requireNonNull(principal, "principal");
  }
}
