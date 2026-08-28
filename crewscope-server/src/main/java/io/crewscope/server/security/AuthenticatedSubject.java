package io.crewscope.server.security;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Trusted authentication coordinate extracted from one supported Spring Security token. */
public sealed interface AuthenticatedSubject
    permits AccountSessionSubject, ExternalAuthenticatedSubject {

  /** Optional deployment-configured Organization boundary carried by an external provider. */
  Optional<OrganizationId> organizationConstraint();
}
