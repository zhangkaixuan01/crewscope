package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.IdentityMappingRequest;
import io.crewscope.application.identity.IdentityMappingResult;
import io.crewscope.application.identity.IdentityMappingService;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.server.security.AuthenticatedSubject;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class RepositoryTeamRequestIdentityResolverTest {

  @Test
  void provisionsTheBootstrapSubjectAndUsesOnlyServerOwnedAdministratorAuthority() {
    OrganizationId organizationId = OrganizationId.generate();
    Principal principal = activeUser(organizationId, "bootstrap", "crewscope");
    IdentityMappingService service = mock(IdentityMappingService.class);
    when(service.map(any())).thenReturn(new IdentityMappingResult(principal, true));
    RepositoryTeamRequestIdentityResolver resolver =
        new RepositoryTeamRequestIdentityResolver(
            service, new AuthenticationSubjectExtractor(Optional.empty()));
    var authentication =
        new UsernamePasswordAuthenticationToken(
            "crewscope", "hidden", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    var correlationId = java.util.UUID.randomUUID();
    var access = resolver.resolve(authentication, organizationId, correlationId).block();

    assertEquals(principal, access.actor());
    assertTrue(access.platformAdministrator());
    ArgumentCaptor<IdentityMappingRequest> request =
        ArgumentCaptor.forClass(IdentityMappingRequest.class);
    verify(service).map(request.capture());
    assertEquals(organizationId, request.getValue().organizationId());
    assertEquals(correlationId, request.getValue().correlationId());
    assertEquals(
        new ExternalIdentity("bootstrap", "crewscope"), request.getValue().externalIdentity());
  }

  @Test
  void rejectsAnOidcOrganizationConstraintBeforeProvisioning() {
    OrganizationId requestedOrganization = OrganizationId.generate();
    IdentityMappingService service = mock(IdentityMappingService.class);
    AuthenticationSubjectExtractor extractor = mock(AuthenticationSubjectExtractor.class);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated("oidc-user", "hidden", List.of());
    when(extractor.extract(authentication))
        .thenReturn(
            new AuthenticatedSubject(
                new ExternalIdentity("oidc/company", "stable-subject"),
                "OIDC User",
                Optional.of(OrganizationId.generate())));
    RepositoryTeamRequestIdentityResolver resolver =
        new RepositoryTeamRequestIdentityResolver(service, extractor);

    org.junit.jupiter.api.Assertions.assertThrows(
        PolicyDeniedException.class,
        () ->
            resolver
                .resolve(authentication, requestedOrganization, java.util.UUID.randomUUID())
                .block());

    verifyNoInteractions(service);
  }

  private static Principal activeUser(
      OrganizationId organizationId, String provider, String subject) {
    return Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "Bootstrap",
        Optional.of(new ExternalIdentity(provider, subject)),
        PrincipalVisibility.ORGANIZATION,
        UtcTimestamp.parse("2026-08-08T03:00:00Z"));
  }
}
