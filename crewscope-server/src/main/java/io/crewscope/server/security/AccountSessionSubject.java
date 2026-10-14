package io.crewscope.server.security;

import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Credential-free Account coordinate restored from the server-side browser Session. */
public record AccountSessionSubject(
    UserAccountId accountId, SecurityVersion securityVersion, IdentityProviderKey identityProvider)
    implements AuthenticatedSubject {

  public AccountSessionSubject {
    accountId = Objects.requireNonNull(accountId, "accountId");
    securityVersion = Objects.requireNonNull(securityVersion, "securityVersion");
    identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
  }

  @Override
  public Optional<OrganizationId> organizationConstraint() {
    return Optional.empty();
  }
}
