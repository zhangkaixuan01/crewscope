package io.crewscope.server.security;

import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Converts supported Spring Security tokens into stable provider/subject identity keys. */
public final class AuthenticationSubjectExtractor {

  private final Optional<OrganizationId> oidcOrganizationId;

  public AuthenticationSubjectExtractor(String oidcOrganizationId) {
    this(parseOptionalOrganizationId(oidcOrganizationId));
  }

  public AuthenticationSubjectExtractor(Optional<OrganizationId> oidcOrganizationId) {
    this.oidcOrganizationId = Objects.requireNonNull(oidcOrganizationId, "oidcOrganizationId");
  }

  public AuthenticatedSubject extract(Authentication authentication) {
    Authentication trusted = Objects.requireNonNull(authentication, "authentication");
    if (!trusted.isAuthenticated()) {
      throw new PolicyDeniedException("use an unauthenticated identity");
    }
    if (trusted instanceof UsernamePasswordAuthenticationToken
        && trusted.getPrincipal() instanceof BrowserSessionPrincipal sessionPrincipal) {
      return new AccountSessionSubject(
          new UserAccountId(sessionPrincipal.accountId()),
          new SecurityVersion(sessionPrincipal.securityVersion()));
    }
    if (trusted instanceof UsernamePasswordAuthenticationToken) {
      return new ExternalAuthenticatedSubject(
          new ExternalIdentity("bootstrap", trusted.getName()),
          trusted.getName(),
          Optional.empty());
    }
    if (trusted instanceof OAuth2AuthenticationToken oauth2
        && oauth2.getPrincipal() instanceof OidcUser user) {
      String subject = requireSubject(user.getSubject());
      String provider = "oidc/" + oauth2.getAuthorizedClientRegistrationId();
      return new ExternalAuthenticatedSubject(
          new ExternalIdentity(provider, subject),
          firstText(
              user.getClaimAsString("name"),
              user.getClaimAsString("preferred_username"),
              user.getClaimAsString("email"),
              subject),
          Optional.of(
              oidcOrganizationId.orElseThrow(
                  () ->
                      new PolicyDeniedException(
                          "use OIDC before its Organization binding is configured"))));
    }
    throw new PolicyDeniedException("use an unsupported authentication type");
  }

  private static String requireSubject(String value) {
    if (value == null || value.isBlank()) {
      throw new PolicyDeniedException("use an OIDC identity without a subject");
    }
    return value.strip();
  }

  private static String firstText(String... values) {
    return Stream.of(values)
        .filter(Objects::nonNull)
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .findFirst()
        .orElseThrow(() -> new PolicyDeniedException("use an identity without a display name"));
  }

  private static Optional<OrganizationId> parseOptionalOrganizationId(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(OrganizationId.from(value.strip()));
  }
}
