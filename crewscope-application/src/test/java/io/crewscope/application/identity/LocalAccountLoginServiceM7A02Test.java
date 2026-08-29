package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Fixed failure, dummy matching and known-account defense transitions for M7-A02. */
class LocalAccountLoginServiceM7A02Test {

    private UserAccountRepository accounts;
    private LoginIdentityRepository identities;
    private LocalCredentialStore credentials;
    private LocalPasswordAuthentication passwords;
    private LoginDefense defense;
    private LocalAccountLoginService service;

    @BeforeEach
    void setUp() {
        accounts = mock(UserAccountRepository.class);
        identities = mock(LoginIdentityRepository.class);
        credentials = mock(LocalCredentialStore.class);
        passwords = mock(LocalPasswordAuthentication.class);
        defense = mock(LoginDefense.class);
        service = new LocalAccountLoginService(
                accounts, identities, credentials, passwords, defense);
    }

    @Test
    void unknownIdentifierPerformsOneDummyMatchWithoutAccountStateMutation() {
        when(accounts.findByEmail(any(NormalizedEmail.class))).thenReturn(Optional.empty());
        when(passwords.verify(eq("wrong-password"), eq(Optional.empty()), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(
                        LocalPasswordVerification.invalidCredentials()));

        assertInvalid(service.authenticate(
                new LocalAccountLoginCommand("missing@example.com", "wrong-password")));

        verify(passwords).verify("wrong-password", Optional.empty(), false);
        verify(defense, never()).observeAccount(any());
        verify(defense, never()).recordFailure(any());
    }

    @Test
    void successfulKnownAccountClearsOnlyItsFailureState() {
        Fixture fixture = fixture();
        when(passwords.verify("correct-password", Optional.of(fixture.material()), true))
                .thenReturn(CompletableFuture.completedFuture(
                        LocalPasswordVerification.authenticated(
                                LocalPasswordVerification.Upgrade.NOT_REQUIRED)));
        when(defense.recordSuccess(fixture.account().id()))
                .thenReturn(CompletableFuture.completedFuture(fixture.state()));

        UserAccount authenticated = service
                .authenticate(new LocalAccountLoginCommand("alice@example.com", "correct-password"))
                .toCompletableFuture()
                .join();

        assertSame(fixture.account(), authenticated);
        verify(defense).recordSuccess(fixture.account().id());
        verify(defense, never()).recordFailure(any());
    }

    @Test
    void lockedAccountUsesDummyDecisionAndRecordsFixedFailure() {
        Fixture fixture = fixture();
        when(fixture.state().temporarilyLocked()).thenReturn(true);
        when(passwords.verify("correct-password", Optional.of(fixture.material()), false))
                .thenReturn(CompletableFuture.completedFuture(
                        LocalPasswordVerification.invalidCredentials()));
        when(defense.recordFailure(fixture.account().id()))
                .thenReturn(CompletableFuture.completedFuture(fixture.state()));

        assertInvalid(service.authenticate(
                new LocalAccountLoginCommand("alice@example.com", "correct-password")));

        verify(defense).recordFailure(fixture.account().id());
        verify(defense, never()).recordSuccess(any());
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = {"LOCKED", "DISABLED"})
    void unavailableAccountStatesRetainTheSameCredentialFailure(AccountStatus status) {
        Fixture fixture = fixture();
        when(fixture.account().canAuthenticate()).thenReturn(status.canAuthenticate());
        when(passwords.verify("correct-password", Optional.of(fixture.material()), false))
                .thenReturn(CompletableFuture.completedFuture(
                        LocalPasswordVerification.invalidCredentials()));
        when(defense.recordFailure(fixture.account().id()))
                .thenReturn(CompletableFuture.completedFuture(fixture.state()));

        assertInvalid(service.authenticate(
                new LocalAccountLoginCommand("alice@example.com", "correct-password")));

        verify(passwords).verify("correct-password", Optional.of(fixture.material()), false);
        verify(defense).recordFailure(fixture.account().id());
        verify(defense, never()).recordSuccess(any());
    }

    private Fixture fixture() {
        UserAccount account = mock(UserAccount.class);
        UserAccountId accountId = UserAccountId.generate();
        when(account.id()).thenReturn(accountId);
        when(account.canAuthenticate()).thenReturn(true);
        when(accounts.findByEmail(any(NormalizedEmail.class))).thenReturn(Optional.of(account));
        LoginIdentity identity = mock(LoginIdentity.class);
        when(identity.isUsable()).thenReturn(true);
        when(identity.provider()).thenReturn(io.crewscope.domain.identity.IdentityProviderKey.local());
        when(identities.findByAccountId(accountId)).thenReturn(List.of(identity));
        LocalCredentialAuthenticationMaterial material =
                mock(LocalCredentialAuthenticationMaterial.class);
        when(credentials.findByAccountIdForAuthentication(accountId))
                .thenReturn(Optional.of(material));
        AccountLoginDefenseState state = mock(AccountLoginDefenseState.class);
        when(state.temporarilyLocked()).thenReturn(false);
        when(defense.observeAccount(accountId))
                .thenReturn(CompletableFuture.completedFuture(state));
        return new Fixture(account, material, state);
    }

    private static void assertInvalid(java.util.concurrent.CompletionStage<?> stage) {
        CompletionException failure = assertThrows(
                CompletionException.class, () -> stage.toCompletableFuture().join());
        assertInstanceOf(LocalAccountLoginException.class, failure.getCause());
    }

    private record Fixture(
            UserAccount account,
            LocalCredentialAuthenticationMaterial material,
            AccountLoginDefenseState state) {}
}
