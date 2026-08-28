package io.crewscope.application.identity;

import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityKey;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Restores an authenticated Account and resolves only its pre-existing Organization Principal. */
public final class AuthenticatedAccountOrganizationResolver {

  private final CurrentAccountSnapshotReader snapshotReader;
  private final LoginIdentityRepository loginIdentityRepository;
  private final AccountOrganizationPrincipalResolver principalResolver;

  public AuthenticatedAccountOrganizationResolver(
      CurrentAccountSnapshotReader snapshotReader,
      LoginIdentityRepository loginIdentityRepository,
      AccountOrganizationPrincipalResolver principalResolver) {
    this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
    this.loginIdentityRepository =
        Objects.requireNonNull(loginIdentityRepository, "loginIdentityRepository");
    this.principalResolver = Objects.requireNonNull(principalResolver, "principalResolver");
  }

  /** Resolves a browser Session only when its security version and local identity remain current. */
  public Optional<AccountOrganizationResolution> resolveSession(
      UserAccountId accountId,
      SecurityVersion sessionSecurityVersion,
      OrganizationId organizationId) {
    UserAccountId requiredAccountId = Objects.requireNonNull(accountId, "accountId");
    SecurityVersion requiredVersion =
        Objects.requireNonNull(sessionSecurityVersion, "sessionSecurityVersion");
    OrganizationId requiredOrganization =
        Objects.requireNonNull(organizationId, "organizationId");
    return snapshotReader
        .findByAccountId(requiredAccountId)
        .filter(snapshot -> snapshot.account().securityVersion().equals(requiredVersion))
        .flatMap(
            snapshot ->
                uniqueUsableLocalIdentity(snapshot)
                    .flatMap(
                        identity ->
                            principalResolver.resolveExisting(
                                snapshot.account(), identity, requiredOrganization)));
  }

  /**
   * Resolves a linked external identity without provisioning. Empty means the identity has never
   * been linked; a linked but unusable identity chain is denied and cannot fall back to legacy
   * Principal lookup.
   */
  public Optional<AccountOrganizationResolution> resolveExternal(
      LoginIdentityKey identityKey, OrganizationId organizationId) {
    LoginIdentityKey requiredKey = Objects.requireNonNull(identityKey, "identityKey");
    OrganizationId requiredOrganization =
        Objects.requireNonNull(organizationId, "organizationId");
    Optional<LoginIdentity> linked = loginIdentityRepository.findByIdentityKey(requiredKey);
    if (linked.isEmpty()) {
      return Optional.empty();
    }
    LoginIdentity identity = linked.orElseThrow();
    if (!identity.isUsable()) {
      throw denied();
    }
    return Optional.of(
        snapshotReader
            .findByAccountId(identity.accountId())
            .flatMap(
                snapshot ->
                    uniqueMatchingIdentity(snapshot, requiredKey)
                        .flatMap(
                            currentIdentity ->
                                principalResolver.resolveExisting(
                                    snapshot.account(),
                                    currentIdentity,
                                    requiredOrganization)))
            .orElseThrow(AuthenticatedAccountOrganizationResolver::denied));
  }

  private static Optional<LoginIdentity> uniqueUsableLocalIdentity(
      CurrentAccountSnapshot snapshot) {
    LoginIdentityKey expected =
        new LoginIdentityKey(
            IdentityProviderKey.local(), LoginIdentitySubject.local(snapshot.account().id()));
    return uniqueMatchingIdentity(snapshot, expected);
  }

  private static Optional<LoginIdentity> uniqueMatchingIdentity(
      CurrentAccountSnapshot snapshot, LoginIdentityKey expected) {
    List<LoginIdentity> matches =
        snapshot.loginIdentities().stream()
            .filter(LoginIdentity::isUsable)
            .filter(identity -> identity.identityKey().equals(expected))
            .toList();
    return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
  }

  private static PolicyDeniedException denied() {
    return new PolicyDeniedException("act with this account in this Organization");
  }
}
