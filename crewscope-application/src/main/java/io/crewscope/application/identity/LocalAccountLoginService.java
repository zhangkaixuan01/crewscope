package io.crewscope.application.identity;

import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.shared.error.DomainException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authenticates one local account while preserving a constant public failure contract. */
public final class LocalAccountLoginService {

    private final UserAccountRepository accounts;
    private final LoginIdentityRepository loginIdentities;
    private final LocalCredentialStore credentials;
    private final LocalPasswordAuthentication passwords;
    private final LoginDefense defense;

    public LocalAccountLoginService(
            UserAccountRepository accounts,
            LoginIdentityRepository loginIdentities,
            LocalCredentialStore credentials,
            LocalPasswordAuthentication passwords,
            LoginDefense defense) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.loginIdentities = Objects.requireNonNull(loginIdentities, "loginIdentities");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.defense = Objects.requireNonNull(defense, "defense");
    }

    /** Runs one real or dummy password match and updates only known-account defense state. */
    public CompletionStage<UserAccount> authenticate(LocalAccountLoginCommand command) {
        LocalAccountLoginCommand requested = Objects.requireNonNull(command, "command");
        Optional<UserAccount> candidate = findCandidate(requested.identifier());
        boolean identityUsable = candidate
                .map(this::hasUniqueUsableLocalIdentity)
                .orElse(false);
        Optional<LocalCredentialAuthenticationMaterial> material = candidate
                .flatMap(account -> credentials.findByAccountIdForAuthentication(account.id()));

        if (candidate.isEmpty()) {
            return passwords.verify(requested.revealPassword(), Optional.empty(), false)
                    .thenCompose(ignored -> failedFuture());
        }
        UserAccount account = candidate.orElseThrow();
        return defense.observeAccount(account.id()).thenCompose(state -> passwords
                .verify(
                        requested.revealPassword(),
                        material,
                        account.canAuthenticate() && identityUsable && !state.temporarilyLocked())
                .thenCompose(verification -> verification.authenticated()
                        ? defense.recordSuccess(account.id()).thenApply(ignored -> account)
                        : defense.recordFailure(account.id()).thenCompose(ignored -> failedFuture())));
    }

    private Optional<UserAccount> findCandidate(String identifier) {
        try {
            String submitted = Objects.requireNonNull(identifier, "identifier");
            return submitted.indexOf('@') >= 0
                    ? accounts.findByEmail(NormalizedEmail.fromDisplayValue(submitted))
                    : accounts.findByUsername(new Username(submitted));
        } catch (DomainException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private boolean hasUniqueUsableLocalIdentity(UserAccount account) {
        List<LoginIdentity> local = loginIdentities.findByAccountId(account.id()).stream()
                .filter(LoginIdentity::isUsable)
                .filter(identity -> identity.provider().isLocal())
                .toList();
        return local.size() == 1;
    }

    private static <T> CompletionStage<T> failedFuture() {
        return CompletableFuture.failedFuture(new LocalAccountLoginException());
    }
}
