package io.crewscope.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class AuthenticationSubjectExtractorTest {

  private final io.crewscope.domain.shared.id.OrganizationId organizationId =
      io.crewscope.domain.shared.id.OrganizationId.generate();
  private final AuthenticationSubjectExtractor extractor =
      new AuthenticationSubjectExtractor(Optional.of(organizationId));

  @Test
  void usesOidcSubAsTheStableKeyAndClaimsOnlyForPresentation() {
    Instant issuedAt = Instant.parse("2026-08-08T03:00:00Z");
    OidcIdToken token =
        new OidcIdToken(
            "token-value",
            issuedAt,
            issuedAt.plusSeconds(300),
            Map.of("sub", "stable-subject", "name", "Kai", "preferred_username", "mutable"));
    DefaultOidcUser user =
        new DefaultOidcUser(
            List.of(new SimpleGrantedAuthority("OIDC_USER")), token, "preferred_username");
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(user, user.getAuthorities(), "company");

    AuthenticatedSubject result = extractor.extract(authentication);

    assertEquals(new ExternalIdentity("oidc/company", "stable-subject"), result.externalIdentity());
    assertEquals("Kai", result.displayName());
    assertEquals(Optional.of(organizationId), result.organizationConstraint());
  }

  @Test
  void rejectsAuthenticationTypesWithoutAnExplicitMappingRule() {
    var anonymous =
        new AnonymousAuthenticationToken(
            "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

    assertThrows(PolicyDeniedException.class, () -> extractor.extract(anonymous));
  }

  @Test
  void rejectsOidcWhenTheDeploymentHasNoOrganizationBinding() {
    Instant issuedAt = Instant.parse("2026-08-08T03:00:00Z");
    OidcIdToken token =
        new OidcIdToken(
            "token-value", issuedAt, issuedAt.plusSeconds(300), Map.of("sub", "stable-subject"));
    DefaultOidcUser user =
        new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), token);
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(user, user.getAuthorities(), "company");

    assertThrows(
        PolicyDeniedException.class,
        () -> new AuthenticationSubjectExtractor(Optional.empty()).extract(authentication));
  }
}
