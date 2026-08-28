package io.crewscope.application.identity;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountIdentityProviderKey;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationBindingStatus;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.OrganizationPrincipalKey;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Atomically creates or validates the one deployment-level Bootstrap Operator identity chain. */
public final class BootstrapOperatorProvisioningService {

    private final BootstrapOperatorLock bootstrapLock;
    private final PrincipalRepository principals;
    private final UserAccountRepository accounts;
    private final LoginIdentityRepository loginIdentities;
    private final LocalCredentialStore credentials;
    private final AccountOrganizationBindingRepository bindings;
    private final BootstrapOperatorPasswordHasher passwordHasher;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public BootstrapOperatorProvisioningService(
            BootstrapOperatorLock bootstrapLock,
            PrincipalRepository principals,
            UserAccountRepository accounts,
            LoginIdentityRepository loginIdentities,
            LocalCredentialStore credentials,
            AccountOrganizationBindingRepository bindings,
            BootstrapOperatorPasswordHasher passwordHasher,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.bootstrapLock = Objects.requireNonNull(bootstrapLock, "bootstrapLock");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.loginIdentities = Objects.requireNonNull(loginIdentities, "loginIdentities");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Executes the complete Principal-to-Account upgrade under one Organization row lock. */
    public BootstrapOperatorProvisioningResult provision(
            BootstrapOperatorProvisioning provisioning) {
        BootstrapOperatorProvisioning required =
                Objects.requireNonNull(provisioning, "provisioning");
        try {
            return transactions.required(() -> provisionLocked(required));
        } catch (BootstrapOperatorProvisioningException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Repository and crypto causes are deliberately detached from the startup error surface.
            throw new BootstrapOperatorProvisioningException();
        }
    }

    private BootstrapOperatorProvisioningResult provisionLocked(
            BootstrapOperatorProvisioning provisioning) {
        bootstrapLock.acquire(provisioning.organizationId());
        UtcTimestamp now = timeProvider.now();
        Principal principal = resolvePrincipal(provisioning, now);
        Optional<AccountOrganizationBinding> existingBinding =
                bindings.findByOrganizationPrincipalKey(
                        new OrganizationPrincipalKey(
                                provisioning.organizationId(), principal.id()));
        if (existingBinding.isPresent()) {
            return validateAndRotateExisting(
                    provisioning, principal, existingBinding.orElseThrow(), now);
        }
        return createIdentityChain(provisioning, principal, now);
    }

    private Principal resolvePrincipal(
            BootstrapOperatorProvisioning provisioning, UtcTimestamp now) {
        Optional<Principal> existing = principals.findByExternalIdentity(
                provisioning.organizationId(),
                BootstrapOperatorProvisioning.LEGACY_PROVIDER,
                BootstrapOperatorProvisioning.LEGACY_SUBJECT);
        Principal principal = existing.orElseGet(() -> principals
                .provisionUser(Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(provisioning.organizationId()),
                        PrincipalType.USER,
                        Optional.empty(),
                        provisioning.displayName(),
                        Optional.of(legacyIdentity()),
                        PrincipalVisibility.ORGANIZATION,
                        now))
                .principal());
        requireCompatiblePrincipal(principal, provisioning.organizationId());
        return principal;
    }

    private BootstrapOperatorProvisioningResult createIdentityChain(
            BootstrapOperatorProvisioning provisioning,
            Principal principal,
            UtcTimestamp now) {
        if (accounts.findByUsername(new Username(provisioning.username())).isPresent()
                || accounts.findByEmail(NormalizedEmail.fromDisplayValue(provisioning.email()))
                        .isPresent()) {
            throw new BootstrapOperatorProvisioningException();
        }

        UserAccount account = accounts.create(UserAccount.bootstrapOperator(
                UserAccountId.generate(),
                provisioning.username(),
                provisioning.email(),
                provisioning.displayName(),
                now));
        LoginIdentity identity = loginIdentities.create(
                LoginIdentity.local(LoginIdentityId.generate(), account.id(), now));
        LocalPasswordHash passwordHash =
                passwordHasher.encode(provisioning.revealPassword());
        LocalCredentialMetadata metadata = LocalCredentialMetadata.create(
                LocalCredentialId.generate(), account.id(), passwordHash, now);
        credentials.create(metadata, passwordHash);
        AccountOrganizationBinding binding = bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                provisioning.organizationId(),
                principal,
                now));
        return result(
                account,
                identity,
                binding,
                principal,
                BootstrapOperatorProvisioningResult.CredentialAction.CREATED);
    }

    private BootstrapOperatorProvisioningResult validateAndRotateExisting(
            BootstrapOperatorProvisioning provisioning,
            Principal principal,
            AccountOrganizationBinding binding,
            UtcTimestamp now) {
        if (binding.status() != AccountOrganizationBindingStatus.ACTIVE
                || !binding.isCompatibleWith(principal)) {
            throw new BootstrapOperatorProvisioningException();
        }
        UserAccount currentAccount = accounts.findByIdForUpdate(binding.accountId())
                .filter(UserAccount::canAuthenticate)
                .filter(value -> value.platformRole() == PlatformRole.OPERATOR)
                .orElseThrow(BootstrapOperatorProvisioningException::new);
        requireConfiguredProfile(currentAccount, provisioning);
        LoginIdentity identity = loginIdentities
                .findByAccountProviderKey(
                        new AccountIdentityProviderKey(
                                currentAccount.id(), IdentityProviderKey.local()))
                .filter(LoginIdentity::isUsable)
                .filter(value -> value.accountId().equals(currentAccount.id()))
                .orElseThrow(BootstrapOperatorProvisioningException::new);
        LocalCredentialAuthenticationMaterial material = credentials
                .findByAccountIdForAuthentication(currentAccount.id())
                .filter(LocalCredentialAuthenticationMaterial::isUsable)
                .orElseThrow(BootstrapOperatorProvisioningException::new);

        BootstrapOperatorPasswordVerification verification = passwordHasher.verify(
                provisioning.revealPassword(), material.passwordHash().orElseThrow());
        if (verification == BootstrapOperatorPasswordVerification.MATCHED) {
            return result(
                    currentAccount,
                    identity,
                    binding,
                    principal,
                    BootstrapOperatorProvisioningResult.CredentialAction.UNCHANGED);
        }

        UtcTimestamp changedAt = nextMutationTime(now, currentAccount, material.metadata());
        LocalPasswordHash replacement = passwordHasher.encode(provisioning.revealPassword());
        LocalCredentialMetadata rotated = material.metadata().rotate(replacement, changedAt);
        if (!credentials.rotateIfUnchanged(
                rotated, replacement, material.metadata().version())) {
            throw new BootstrapOperatorProvisioningException();
        }
        BootstrapOperatorProvisioningResult.CredentialAction action;
        UserAccount resultAccount = currentAccount;
        if (verification == BootstrapOperatorPasswordVerification.MATCHED_REHASH_REQUIRED) {
            action = BootstrapOperatorProvisioningResult.CredentialAction.REHASHED;
        } else {
            resultAccount = accounts.update(
                    currentAccount.advanceSecurityVersion(changedAt), currentAccount.version());
            action = BootstrapOperatorProvisioningResult.CredentialAction.ROTATED;
        }
        return result(resultAccount, identity, binding, principal, action);
    }

    private static BootstrapOperatorProvisioningResult result(
            UserAccount account,
            LoginIdentity identity,
            AccountOrganizationBinding binding,
            Principal principal,
            BootstrapOperatorProvisioningResult.CredentialAction credentialAction) {
        return new BootstrapOperatorProvisioningResult(
                account.id(), identity.id(), binding.id(), principal.id(), credentialAction);
    }

    private static void requireCompatiblePrincipal(
            Principal principal, OrganizationId organizationId) {
        Principal required = Objects.requireNonNull(principal, "principal");
        if (!required.canAct()
                || required.type() != PrincipalType.USER
                || !required.scope().organizationId().equals(organizationId)
                || required.scope().teamId().isPresent()
                || required.visibility() != PrincipalVisibility.ORGANIZATION
                || required.ownerPrincipalId().isPresent()
                || required.externalIdentity().filter(legacyIdentity()::equals).isEmpty()) {
            throw new BootstrapOperatorProvisioningException();
        }
    }

    private static void requireConfiguredProfile(
            UserAccount account, BootstrapOperatorProvisioning provisioning) {
        Username configuredUsername = new Username(provisioning.username());
        NormalizedEmail configuredEmail =
                NormalizedEmail.fromDisplayValue(provisioning.email());
        if (!account.username().equals(configuredUsername)
                || !account.normalizedEmail().equals(configuredEmail)
                || !account.displayName().equals(provisioning.displayName())) {
            // Deployment coordinates are not a profile-management channel. Failing closed avoids
            // rotating a password for an Account that the configured login identifier cannot find.
            throw new BootstrapOperatorProvisioningException();
        }
    }

    private static ExternalIdentity legacyIdentity() {
        return new ExternalIdentity(
                BootstrapOperatorProvisioning.LEGACY_PROVIDER,
                BootstrapOperatorProvisioning.LEGACY_SUBJECT);
    }

    private static UtcTimestamp nextMutationTime(
            UtcTimestamp now,
            UserAccount account,
            LocalCredentialMetadata credential) {
        Instant minimum = account.lifecycle().updatedAt().value();
        if (credential.lifecycle().updatedAt().value().isAfter(minimum)) {
            minimum = credential.lifecycle().updatedAt().value();
        }
        Instant candidate = now.value().isAfter(minimum)
                ? now.value()
                : minimum.plus(1, ChronoUnit.MICROS);
        return UtcTimestamp.from(candidate);
    }
}
