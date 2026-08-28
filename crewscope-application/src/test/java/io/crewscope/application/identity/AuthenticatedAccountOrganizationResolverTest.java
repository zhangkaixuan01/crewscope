package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityStatus;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthenticatedAccountOrganizationResolverTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-08T03:00:00Z");
  private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-08T03:01:00Z");

  @Test
  void resolvesOnlyTheCurrentLocalSessionAndExistingBinding() {
    Fixture fixture = fixture();
    CurrentAccountSnapshotReader snapshots = mock(CurrentAccountSnapshotReader.class);
    when(snapshots.findByAccountId(fixture.account.id()))
        .thenReturn(Optional.of(fixture.snapshot()));
    AuthenticatedAccountOrganizationResolver resolver = resolver(fixture, snapshots);

    var result =
        resolver.resolveSession(
            fixture.account.id(), fixture.account.securityVersion(), fixture.organizationId);

    assertTrue(result.isPresent());
    assertEquals(fixture.principal, result.orElseThrow().principal());
  }

  @Test
  void rejectsStaleSecurityVersionBeforeBindingLookup() {
    Fixture fixture = fixture();
    CurrentAccountSnapshotReader snapshots = mock(CurrentAccountSnapshotReader.class);
    when(snapshots.findByAccountId(fixture.account.id()))
        .thenReturn(Optional.of(fixture.snapshot()));
    AccountOrganizationBindingRepository bindings =
        mock(AccountOrganizationBindingRepository.class);
    AccountOrganizationPrincipalResolver principals =
        new AccountOrganizationPrincipalResolver(
            bindings, (organization, principal) -> Optional.empty());
    AuthenticatedAccountOrganizationResolver resolver =
        new AuthenticatedAccountOrganizationResolver(
            snapshots, mock(LoginIdentityRepository.class), principals);

    var result =
        resolver.resolveSession(
            fixture.account.id(),
            new SecurityVersion(fixture.account.securityVersion().value() + 1),
            fixture.organizationId);

    assertTrue(result.isEmpty());
    verifyNoInteractions(bindings);
  }

  @Test
  void rejectsDisabledAccountLocalIdentityBindingAndPrincipal() {
    Fixture fixture = fixture();

    UserAccount disabledAccount = fixture.account.transitionTo(AccountStatus.DISABLED, LATER);
    assertTrue(resolveSession(fixture.withAccount(disabledAccount)).isEmpty());

    LoginIdentity disabledIdentity =
        fixture.identity.transitionTo(LoginIdentityStatus.DISABLED, LATER);
    assertTrue(resolveSession(fixture.withIdentity(disabledIdentity)).isEmpty());

    AccountOrganizationBinding disabledBinding = fixture.binding.disable(LATER);
    assertTrue(resolveSession(fixture.withBinding(disabledBinding)).isEmpty());

    Principal disabledPrincipal =
        fixture.principal.transitionTo(PrincipalStatus.DISABLED, LATER);
    assertTrue(resolveSession(fixture.withPrincipal(disabledPrincipal)).isEmpty());
  }

  @Test
  void rejectsAnotherOrganizationWithoutCreatingABinding() {
    Fixture fixture = fixture();
    CurrentAccountSnapshotReader snapshots = mock(CurrentAccountSnapshotReader.class);
    when(snapshots.findByAccountId(fixture.account.id()))
        .thenReturn(Optional.of(fixture.snapshot()));
    AccountOrganizationBindingRepository bindings =
        mock(AccountOrganizationBindingRepository.class);
    when(bindings.findByAccountOrganizationKey(any())).thenReturn(Optional.empty());
    AuthenticatedAccountOrganizationResolver resolver =
        new AuthenticatedAccountOrganizationResolver(
            snapshots,
            mock(LoginIdentityRepository.class),
            new AccountOrganizationPrincipalResolver(
                bindings, (organization, principal) -> Optional.of(fixture.principal)));

    var result =
        resolver.resolveSession(
            fixture.account.id(), fixture.account.securityVersion(), OrganizationId.generate());

    assertTrue(result.isEmpty());
  }

  @Test
  void resolvesALinkedExternalIdentityWithoutProvisioning() {
    Fixture fixture = fixture();
    IdentityProviderKey provider = new IdentityProviderKey("oidc/company");
    LoginIdentity external =
        LoginIdentity.external(
            LoginIdentityId.generate(),
            fixture.account.id(),
            provider,
            new LoginIdentitySubject("stable-subject"),
            NOW);
    Fixture linked = fixture.withIdentity(external);
    CurrentAccountSnapshotReader snapshots = mock(CurrentAccountSnapshotReader.class);
    when(snapshots.findByAccountId(linked.account.id()))
        .thenReturn(Optional.of(linked.snapshot()));
    LoginIdentityRepository identities = mock(LoginIdentityRepository.class);
    when(identities.findByIdentityKey(external.identityKey())).thenReturn(Optional.of(external));
    AuthenticatedAccountOrganizationResolver resolver = resolver(linked, snapshots, identities);

    var result = resolver.resolveExternal(external.identityKey(), linked.organizationId);

    assertTrue(result.isPresent());
    assertEquals(linked.account, result.orElseThrow().account());
  }

  @Test
  void linkedButDisabledExternalIdentityCannotFallBackToLegacyLookup() {
    Fixture fixture = fixture();
    IdentityProviderKey provider = new IdentityProviderKey("oidc/company");
    LoginIdentity disabled =
        LoginIdentity.external(
                LoginIdentityId.generate(),
                fixture.account.id(),
                provider,
                new LoginIdentitySubject("stable-subject"),
                NOW)
            .transitionTo(LoginIdentityStatus.DISABLED, LATER);
    LoginIdentityRepository identities = mock(LoginIdentityRepository.class);
    when(identities.findByIdentityKey(disabled.identityKey())).thenReturn(Optional.of(disabled));
    CurrentAccountSnapshotReader snapshots = mock(CurrentAccountSnapshotReader.class);
    AuthenticatedAccountOrganizationResolver resolver =
        new AuthenticatedAccountOrganizationResolver(
            snapshots,
            identities,
            new AccountOrganizationPrincipalResolver(
                mock(AccountOrganizationBindingRepository.class),
                (organization, principal) -> Optional.empty()));

    assertThrows(
        PolicyDeniedException.class,
        () -> resolver.resolveExternal(disabled.identityKey(), fixture.organizationId));

    verifyNoInteractions(snapshots);
  }

  private static Optional<AccountOrganizationResolution> resolveSession(Fixture fixture) {
    CurrentAccountSnapshotReader snapshots = mock(CurrentAccountSnapshotReader.class);
    when(snapshots.findByAccountId(fixture.account.id()))
        .thenReturn(Optional.of(fixture.snapshot()));
    AuthenticatedAccountOrganizationResolver resolver = resolver(fixture, snapshots);
    return resolver.resolveSession(
        fixture.account.id(), fixture.account.securityVersion(), fixture.organizationId);
  }

  private static AuthenticatedAccountOrganizationResolver resolver(
      Fixture fixture, CurrentAccountSnapshotReader snapshots) {
    return resolver(fixture, snapshots, mock(LoginIdentityRepository.class));
  }

  private static AuthenticatedAccountOrganizationResolver resolver(
      Fixture fixture,
      CurrentAccountSnapshotReader snapshots,
      LoginIdentityRepository identities) {
    AccountOrganizationBindingRepository bindings =
        mock(AccountOrganizationBindingRepository.class);
    when(bindings.findByAccountOrganizationKey(any())).thenReturn(Optional.of(fixture.binding));
    AccountOrganizationPrincipalResolver principals =
        new AccountOrganizationPrincipalResolver(
            bindings, (organization, principal) -> Optional.of(fixture.principal));
    return new AuthenticatedAccountOrganizationResolver(snapshots, identities, principals);
  }

  private static Fixture fixture() {
    OrganizationId organizationId = OrganizationId.generate();
    UserAccount account =
        UserAccount.register(
            UserAccountId.generate(), "member", "member@example.test", "Member", NOW);
    LoginIdentity identity = LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW);
    Principal principal =
        Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Member",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    AccountOrganizationBinding binding =
        AccountOrganizationBinding.bind(
            AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);
    return new Fixture(organizationId, account, identity, binding, principal);
  }

  private record Fixture(
      OrganizationId organizationId,
      UserAccount account,
      LoginIdentity identity,
      AccountOrganizationBinding binding,
      Principal principal) {

    private CurrentAccountSnapshot snapshot() {
      return new CurrentAccountSnapshot(account, List.of(identity), Optional.empty(), List.of(binding));
    }

    private Fixture withAccount(UserAccount replacement) {
      return new Fixture(organizationId, replacement, identity, binding, principal);
    }

    private Fixture withIdentity(LoginIdentity replacement) {
      return new Fixture(organizationId, account, replacement, binding, principal);
    }

    private Fixture withBinding(AccountOrganizationBinding replacement) {
      return new Fixture(organizationId, account, identity, replacement, principal);
    }

    private Fixture withPrincipal(Principal replacement) {
      return new Fixture(organizationId, account, identity, binding, replacement);
    }
  }
}
