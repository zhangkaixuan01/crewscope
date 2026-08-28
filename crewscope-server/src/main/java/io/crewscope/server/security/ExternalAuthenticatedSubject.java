package io.crewscope.server.security;

import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Bootstrap or OIDC subject whose stable provider coordinate is supplied by Spring Security. */
public record ExternalAuthenticatedSubject(
    ExternalIdentity externalIdentity,
    String displayName,
    Optional<OrganizationId> organizationConstraint)
    implements AuthenticatedSubject {

  public ExternalAuthenticatedSubject {
    externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
    displayName = normalizeDisplayName(displayName);
    organizationConstraint =
        Objects.requireNonNull(organizationConstraint, "organizationConstraint");
  }

  private static String normalizeDisplayName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    String normalized = value.strip();
    // Identity-provider claims are untrusted input; truncate presentation text at the edge.
    return normalized.length() <= Principal.MAX_DISPLAY_NAME_LENGTH
        ? normalized
        : normalized.substring(0, Principal.MAX_DISPLAY_NAME_LENGTH);
  }
}
