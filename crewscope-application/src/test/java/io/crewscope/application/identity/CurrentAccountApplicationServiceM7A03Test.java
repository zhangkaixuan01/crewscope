package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.LocalCredentialId;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.event.AccountLoggedOut;
import io.crewscope.domain.identity.event.AccountLogoutScope;
import io.crewscope.domain.identity.event.AccountPasswordChanged;
import io.crewscope.domain.identity.event.AccountProfileChanged;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Profile, password, security-version and audit behavior frozen by M7-A03. */
class CurrentAccountApplicationServiceM7A03Test {

    private static final UtcTimestamp CREATED = UtcTimestamp.parse("2026-08-29T00:00:00Z");
    private static final UtcTimestamp CHANGED = UtcTimestamp.parse("2026-08-29T01:00:00Z");

    private UserAccountRepository accounts;
    private LocalCredentialStore credentials;
    private LocalPasswordAuthentication passwords;
    private DomainEventStore events;
    private OutboxRepository outbox;
    private AtomicReference<UserAccount> persisted;
    private List<DomainEventEnvelope<?>> appended;
    private CurrentAccountApplicationService service;
    private CurrentAccountCommandContext context;

    @BeforeEach
    void setUp() {
        accounts = mock(UserAccountRepository.class);
        credentials = mock(LocalCredentialStore.class);
        passwords = mock(LocalPasswordAuthentication.class);
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        persisted = new AtomicReference<>(UserAccount.register(
                UserAccountId.generate(), "alice", "alice@example.com", "Alice", CREATED));
        when(accounts.findById(any())).thenAnswer(ignored -> Optional.of(persisted.get()));
        when(accounts.findByIdForUpdate(any())).thenAnswer(ignored -> Optional.of(persisted.get()));
        when(accounts.update(any(), anyLong())).thenAnswer(invocation -> {
            UserAccount changed = invocation.getArgument(0);
            persisted.set(changed);
            return changed;
        });
        appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(invocation.getArgument(0));
            return null;
        }).when(events).append(any());
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        service = new CurrentAccountApplicationService(
                accounts,
                credentials,
                passwords,
                events,
                outbox,
                transactions,
                () -> CHANGED,
                Runnable::run);
        context = new CurrentAccountCommandContext(
                persisted.get().id(),
                OrganizationId.generate(),
                PrincipalId.generate(),
                UUID.randomUUID(),
                Optional.empty());
    }

    @Test
    void displayNameOnlyAdvancesOneBusinessVersionWithoutPasswordWork() {
        CurrentAccountMutationResult result = service.updateProfile(
                        context,
                        new AccountProfileUpdateCommand(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("Alice Zhang"),
                                Optional.empty(),
                                OptionalLong.empty(),
                                0))
                .toCompletableFuture()
                .join();

        assertEquals(1, result.account().version());
        assertEquals(1, result.account().securityVersion().value());
        assertEquals("Alice Zhang", result.account().displayName());
        AccountProfileChanged payload = assertInstanceOf(
                AccountProfileChanged.class, appended.get(0).payload());
        assertTrue(payload.displayNameChanged());
        verify(passwords, never()).verify(any(), any(), anyBoolean());
    }

    @Test
    void identifierChangeUsesCurrentPasswordAndOneUnifiedAuditSummary() {
        credentialAndSuccessfulVerification("correct-current");

        CurrentAccountMutationResult result = service.updateProfile(
                        context,
                        new AccountProfileUpdateCommand(
                                Optional.of("alice.dev"),
                                Optional.of("alice.dev@example.com"),
                                Optional.empty(),
                                Optional.of("correct-current"),
                                OptionalLong.of(1),
                                0))
                .toCompletableFuture()
                .join();

        assertEquals(1, result.account().version());
        AccountProfileChanged payload = assertInstanceOf(
                AccountProfileChanged.class, appended.get(0).payload());
        assertTrue(payload.usernameChanged());
        assertTrue(payload.emailChanged());
        assertEquals(
                "AccountProfileChanged[usernameChanged=true, emailChanged=true, displayNameChanged=false]",
                payload.toString());
    }

    @Test
    void wrongCurrentPasswordCannotMutateAnIdentifier() {
        LocalCredentialAuthenticationMaterial material = credential();
        when(passwords.verify("wrong-current", Optional.of(material), true))
                .thenReturn(CompletableFuture.completedFuture(
                        LocalPasswordVerification.invalidCredentials()));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> service.updateProfile(
                                context,
                                new AccountProfileUpdateCommand(
                                        Optional.of("alice.dev"),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.of("wrong-current"),
                                        OptionalLong.of(1),
                                        0))
                        .toCompletableFuture()
                        .join());

        CurrentAccountMutationException accountFailure = assertInstanceOf(
                CurrentAccountMutationException.class, failure.getCause());
        assertEquals(
                CurrentAccountMutationFailure.INVALID_CURRENT_PASSWORD,
                accountFailure.failure());
        verify(accounts, never()).update(any(), anyLong());
    }

    @Test
    void passwordChangeRotatesCredentialAndInvalidatesTheOldSecurityEpoch() {
        LocalCredentialAuthenticationMaterial material = credentialAndSuccessfulVerification(
                "correct-current");
        LocalPasswordHash replacementHash = hash("replacement-password-hash");
        when(passwords.encodeForStorage("new-password-value"))
                .thenReturn(CompletableFuture.completedFuture(replacementHash));
        when(credentials.rotateIfUnchanged(any(), any(), anyLong())).thenReturn(true);

        CurrentAccountMutationResult result = service.changePassword(
                        context,
                        new AccountPasswordChangeCommand(
                                "correct-current", "new-password-value", 1, 0))
                .toCompletableFuture()
                .join();

        assertEquals(2, result.account().securityVersion().value());
        assertEquals(1, result.account().version());
        AccountPasswordChanged payload = assertInstanceOf(
                AccountPasswordChanged.class, appended.get(0).payload());
        assertEquals(2, payload.credentialVersion());
        assertEquals(2, payload.securityVersion());
        verify(credentials).rotateIfUnchanged(any(), any(), anyLong());
        assertTrue(material.toString().contains("passwordHash=REDACTED"));
    }

    @Test
    void allSessionRevocationRequiresCurrentSecurityVersionAndAuditsOnlyTheEpoch() {
        credentialAndSuccessfulVerification("correct-current");

        CurrentAccountMutationResult result = service.revokeAllSessions(
                        context,
                        new AccountSessionRevocationCommand("correct-current", 1, 0))
                .toCompletableFuture()
                .join();

        assertEquals(2, result.account().securityVersion().value());
        AccountLoggedOut payload = assertInstanceOf(
                AccountLoggedOut.class, appended.get(0).payload());
        assertEquals(AccountLogoutScope.ALL_SESSIONS, payload.scope());
        assertEquals(2, payload.securityVersion());
    }

    @Test
    void staleStrongVersionStopsBeforePasswordVerification() {
        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.revokeAllSessions(
                        context, new AccountSessionRevocationCommand("never-used", 1, 7)));

        verify(passwords, never()).verify(any(), any(), anyBoolean());
    }

    private LocalCredentialAuthenticationMaterial credentialAndSuccessfulVerification(
            String currentPassword) {
        LocalCredentialAuthenticationMaterial material = credential();
        when(passwords.verify(currentPassword, Optional.of(material), true))
                .thenReturn(CompletableFuture.completedFuture(
                        LocalPasswordVerification.authenticated(
                                LocalPasswordVerification.Upgrade.NOT_REQUIRED)));
        return material;
    }

    private LocalCredentialAuthenticationMaterial credential() {
        LocalPasswordHash hash = hash("current-password-hash");
        LocalCredentialMetadata metadata = LocalCredentialMetadata.create(
                LocalCredentialId.generate(), persisted.get().id(), hash, CREATED);
        LocalCredentialAuthenticationMaterial material =
                LocalCredentialAuthenticationMaterial.verified(metadata, hash);
        when(credentials.findByAccountIdForAuthentication(persisted.get().id()))
                .thenReturn(Optional.of(material));
        return material;
    }

    private static LocalPasswordHash hash(String body) {
        return new LocalPasswordHash("{argon2id}" + body + "-0123456789");
    }
}
