package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.AccountOrganizationResolution;
import io.crewscope.application.identity.AuthenticatedAccountOrganizationResolver;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.server.security.AuthenticationSubjectExtractor;
import io.crewscope.server.security.ExternalAuthenticatedSubject;
import io.crewscope.server.security.session.BrowserSessionPrincipal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class RepositoryTeamRequestIdentityResolverTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T03:00:00Z");

  @Test
  void resolvesSessionThroughExistingBindingAndIgnoresForgedSessionOperatorAuthority() {
    Fixture fixture = fixture(PlatformRole.USER);
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    when(accounts.resolveSession(
            fixture.account.id(), fixture.account.securityVersion(), fixture.organizationId))
        .thenReturn(Optional.of(fixture.resolution()));
    PrincipalRepository principals = mock(PrincipalRepository.class);
    RepositoryTeamRequestIdentityResolver resolver = resolver(accounts, principals);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            new BrowserSessionPrincipal(
                fixture.account.id().value(), fixture.account.securityVersion().value()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));

    var access =
        resolver.resolve(authentication, fixture.organizationId, UUID.randomUUID()).block();

    assertEquals(fixture.principal, access.actor());
    assertFalse(access.platformAdministrator());
    verifyNoInteractions(principals);
  }

  @Test
  void derivesOperatorAuthorityFromThePersistedAccount() {
    Fixture fixture = fixture(PlatformRole.OPERATOR);
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    when(accounts.resolveSession(
            fixture.account.id(), fixture.account.securityVersion(), fixture.organizationId))
        .thenReturn(Optional.of(fixture.resolution()));
    RepositoryTeamRequestIdentityResolver resolver =
        resolver(accounts, mock(PrincipalRepository.class));
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            new BrowserSessionPrincipal(
                fixture.account.id().value(), fixture.account.securityVersion().value()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    var access =
        resolver.resolve(authentication, fixture.organizationId, UUID.randomUUID()).block();

    assertTrue(access.platformAdministrator());
  }

  @Test
  void rejectsSessionWithoutAnExistingOrganizationResolution() {
    Fixture fixture = fixture(PlatformRole.USER);
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    when(accounts.resolveSession(any(), any(), any())).thenReturn(Optional.empty());
    PrincipalRepository principals = mock(PrincipalRepository.class);
    RepositoryTeamRequestIdentityResolver resolver = resolver(accounts, principals);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            new BrowserSessionPrincipal(
                fixture.account.id().value(), fixture.account.securityVersion().value()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    assertThrows(
        PolicyDeniedException.class,
        () -> resolver.resolve(authentication, OrganizationId.generate(), UUID.randomUUID()).block());

    verifyNoInteractions(principals);
  }

  @Test
  void resolvesLinkedExternalIdentityThroughItsPersistedAccountRole() {
    Fixture fixture = fixture(PlatformRole.USER);
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    when(accounts.resolveExternal(any(), any())).thenReturn(Optional.of(fixture.resolution()));
    PrincipalRepository principals = mock(PrincipalRepository.class);
    AuthenticationSubjectExtractor extractor = mock(AuthenticationSubjectExtractor.class);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            "linked-user",
            "hidden",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(extractor.extract(authentication))
        .thenReturn(
            new ExternalAuthenticatedSubject(
                new ExternalIdentity("oidc/company", "stable-subject"),
                "Linked User",
                Optional.of(fixture.organizationId)));
    RepositoryTeamRequestIdentityResolver resolver =
        new RepositoryTeamRequestIdentityResolver(accounts, principals, extractor);

    var access =
        resolver.resolve(authentication, fixture.organizationId, UUID.randomUUID()).block();

    assertEquals(fixture.principal, access.actor());
    assertFalse(access.platformAdministrator());
    verifyNoInteractions(principals);
  }

  @Test
  void legacyBootstrapCanOnlyReadAnExistingPrincipalWithoutOperatorAuthority() {
    OrganizationId organizationId = OrganizationId.generate();
    Principal principal = activeUser(organizationId, "bootstrap", "crewscope");
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    when(accounts.resolveExternal(any(), any())).thenReturn(Optional.empty());
    PrincipalRepository principals = mock(PrincipalRepository.class);
    when(principals.findByExternalIdentity(organizationId, "bootstrap", "crewscope"))
        .thenReturn(Optional.of(principal));
    RepositoryTeamRequestIdentityResolver resolver = resolver(accounts, principals);
    var authentication =
        new UsernamePasswordAuthenticationToken(
            "crewscope", "hidden", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    var access = resolver.resolve(authentication, organizationId, UUID.randomUUID()).block();

    assertEquals(principal, access.actor());
    assertFalse(access.platformAdministrator());
    verify(principals).findByExternalIdentity(organizationId, "bootstrap", "crewscope");
    verify(principals, never()).provisionUser(any());
  }

  @Test
  void linkedButRevokedExternalAccountCannotFallBackToLegacyPrincipal() {
    OrganizationId organizationId = OrganizationId.generate();
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    when(accounts.resolveExternal(any(), any()))
        .thenThrow(new PolicyDeniedException("act with this account in this Organization"));
    PrincipalRepository principals = mock(PrincipalRepository.class);
    RepositoryTeamRequestIdentityResolver resolver = resolver(accounts, principals);
    var authentication =
        new UsernamePasswordAuthenticationToken(
            "crewscope", "hidden", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    assertThrows(
        PolicyDeniedException.class,
        () -> resolver.resolve(authentication, organizationId, UUID.randomUUID()).block());

    verifyNoInteractions(principals);
  }

  @Test
  void rejectsAnExternalOrganizationConstraintBeforeRepositoryAccess() {
    OrganizationId requestedOrganization = OrganizationId.generate();
    AuthenticatedAccountOrganizationResolver accounts =
        mock(AuthenticatedAccountOrganizationResolver.class);
    PrincipalRepository principals = mock(PrincipalRepository.class);
    AuthenticationSubjectExtractor extractor = mock(AuthenticationSubjectExtractor.class);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated("oidc-user", "hidden", List.of());
    when(extractor.extract(authentication))
        .thenReturn(
            new ExternalAuthenticatedSubject(
                new ExternalIdentity("oidc/company", "stable-subject"),
                "OIDC User",
                Optional.of(OrganizationId.generate())));
    RepositoryTeamRequestIdentityResolver resolver =
        new RepositoryTeamRequestIdentityResolver(accounts, principals, extractor);

    assertThrows(
        PolicyDeniedException.class,
        () ->
            resolver.resolve(authentication, requestedOrganization, UUID.randomUUID()).block());

    verifyNoInteractions(accounts, principals);
  }

  private static RepositoryTeamRequestIdentityResolver resolver(
      AuthenticatedAccountOrganizationResolver accounts, PrincipalRepository principals) {
    return new RepositoryTeamRequestIdentityResolver(
        accounts, principals, new AuthenticationSubjectExtractor(Optional.empty()));
  }

  private static Fixture fixture(PlatformRole role) {
    OrganizationId organizationId = OrganizationId.generate();
    UserAccountId accountId = UserAccountId.generate();
    UserAccount account =
        role == PlatformRole.OPERATOR
            ? UserAccount.bootstrapOperator(
                accountId, "operator", "operator@example.test", "Operator", NOW)
            : UserAccount.register(accountId, "member", "member@example.test", "Member", NOW);
    LoginIdentity identity = LoginIdentity.local(LoginIdentityId.generate(), accountId, NOW);
    Principal principal = activeLocalUser(organizationId);
    AccountOrganizationBinding binding =
        AccountOrganizationBinding.bind(
            AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);
    return new Fixture(organizationId, account, identity, binding, principal);
  }

  private static Principal activeUser(
      OrganizationId organizationId, String provider, String subject) {
    return Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "User",
        Optional.of(new ExternalIdentity(provider, subject)),
        PrincipalVisibility.ORGANIZATION,
        NOW);
  }

  private static Principal activeLocalUser(OrganizationId organizationId) {
    return Principal.create(
        PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        "User",
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        NOW);
  }

  private record Fixture(
      OrganizationId organizationId,
      UserAccount account,
      LoginIdentity identity,
      AccountOrganizationBinding binding,
      Principal principal) {

    private AccountOrganizationResolution resolution() {
      return new AccountOrganizationResolution(account, identity, binding, principal);
    }
  }
}
