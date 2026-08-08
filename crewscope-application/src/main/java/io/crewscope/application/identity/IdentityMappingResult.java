package io.crewscope.application.identity;

import io.crewscope.domain.identity.Principal;
import java.util.Objects;

/** Principal resolved for an authenticated external subject and whether this request created it. */
public record IdentityMappingResult(Principal principal, boolean created) {

  public IdentityMappingResult {
    principal = Objects.requireNonNull(principal, "principal");
  }
}
