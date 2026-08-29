package io.crewscope.application.identity;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.event.AccountLoggedOut;
import io.crewscope.domain.identity.event.AccountLogoutScope;
import io.crewscope.domain.identity.event.AccountPasswordChanged;
import io.crewscope.domain.identity.event.AccountProfileChanged;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Reads and mutates only the authenticated account represented by trusted Session coordinates. */
public final class CurrentAccountApplicationService {

    private static final String ACCOUNT_AGGREGATE = "USER_ACCOUNT";

    private final UserAccountRepository accounts;
    private final LocalCredentialStore credentials;
    private final LocalPasswordAuthentication passwords;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final Executor persistenceExecutor;

    public CurrentAccountApplicationService(
            UserAccountRepository accounts,
            LocalCredentialStore credentials,
            LocalPasswordAuthentication passwords,
            DomainEventStore events,
            OutboxRepository outbox,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            Executor persistenceExecutor) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
    }

    /** Returns the current persisted profile without any Credential or Session material. */
    public UserAccount current(CurrentAccountCommandContext context) {
        CurrentAccountCommandContext trusted = Objects.requireNonNull(context, "context");
        return requireAccount(trusted);
    }

    /** Applies one profile revision and requires step-up authentication for identifier changes. */
    public CompletionStage<CurrentAccountMutationResult> updateProfile(
            CurrentAccountCommandContext context, AccountProfileUpdateCommand command) {
        CurrentAccountCommandContext trusted = Objects.requireNonNull(context, "context");
        AccountProfileUpdateCommand requested = Objects.requireNonNull(command, "command");
        UserAccount observed = requireAccount(trusted);
        requireVersion(observed, requested.expectedVersion());
        boolean sensitive = requested.username()
                        .filter(value -> !value.equals(observed.username().displayValue()))
                        .isPresent()
                || requested.email().filter(value -> !value.equals(observed.email())).isPresent();
        CompletionStage<Void> stepUp = sensitive
                ? requireStepUp(
                        observed,
                        requested.revealCurrentPassword(),
                        requested.expectedSecurityVersion())
                : CompletableFuture.completedFuture(null);
        return stepUp.thenApplyAsync(
                ignored -> transactions.required(
                        () -> commitProfile(trusted, requested, sensitive)),
                persistenceExecutor);
    }

    /** Replaces the local Credential and advances the security epoch in the same transaction. */
    public CompletionStage<CurrentAccountMutationResult> changePassword(
            CurrentAccountCommandContext context, AccountPasswordChangeCommand command) {
        CurrentAccountCommandContext trusted = Objects.requireNonNull(context, "context");
        AccountPasswordChangeCommand requested = Objects.requireNonNull(command, "command");
        UserAccount observed = requireAccount(trusted);
        requireVersion(observed, requested.expectedVersion());
        requireSecurityVersion(observed, requested.expectedSecurityVersion());
        LocalCredentialAuthenticationMaterial observedCredential = requireCredential(observed);
        return verifyCurrentPassword(
                        observed, observedCredential, requested.revealCurrentPassword())
                // Verification may transparently rehash an old Credential, so reload its version.
                .thenApplyAsync(
                        ignored -> passwordChangePreparation(trusted, requested),
                        persistenceExecutor)
                .thenCompose(preparation -> passwords.encodeForStorage(requested.revealNewPassword())
                        .thenApply(hash -> new EncodedPasswordChange(preparation, hash)))
                .thenApplyAsync(
                        encoded -> transactions.required(
                                () -> commitPassword(trusted, requested, encoded)),
                        persistenceExecutor);
    }

    /** Advances the security epoch before the HTTP boundary deletes every Redis Session. */
    public CompletionStage<CurrentAccountMutationResult> revokeAllSessions(
            CurrentAccountCommandContext context, AccountSessionRevocationCommand command) {
        CurrentAccountCommandContext trusted = Objects.requireNonNull(context, "context");
        AccountSessionRevocationCommand requested = Objects.requireNonNull(command, "command");
        UserAccount observed = requireAccount(trusted);
        requireVersion(observed, requested.expectedVersion());
        requireSecurityVersion(observed, requested.expectedSecurityVersion());
        return verifyCurrentPassword(
                        observed, requireCredential(observed), requested.revealCurrentPassword())
                .thenApplyAsync(
                        ignored -> transactions.required(
                                () -> commitSessionRevocation(trusted, requested)),
                        persistenceExecutor);
    }

    private CurrentAccountMutationResult commitProfile(
            CurrentAccountCommandContext context,
            AccountProfileUpdateCommand command,
            boolean sensitive) {
        UserAccount current = lockAccount(context);
        requireVersion(current, command.expectedVersion());
        if (sensitive) {
            requireSecurityVersion(current, command.expectedSecurityVersion().orElseThrow());
        }
        UtcTimestamp now = timeProvider.now();
        UserAccount changed = current.changeProfile(
                command.username(), command.email(), command.displayName(), now);
        UserAccount committed = accounts.update(changed, current.version());
        AccountProfileChanged payload = new AccountProfileChanged(
                !current.username().equals(committed.username()),
                !current.email().equals(committed.email()),
                !current.displayName().equals(committed.displayName()));
        return append(context, committed, "ACCOUNT_PROFILE_CHANGED", payload, now);
    }

    private PasswordChangePreparation passwordChangePreparation(
            CurrentAccountCommandContext context, AccountPasswordChangeCommand command) {
        UserAccount current = requireAccount(context);
        requireVersion(current, command.expectedVersion());
        requireSecurityVersion(current, command.expectedSecurityVersion());
        return new PasswordChangePreparation(requireCredential(current).metadata());
    }

    private CurrentAccountMutationResult commitPassword(
            CurrentAccountCommandContext context,
            AccountPasswordChangeCommand command,
            EncodedPasswordChange encoded) {
        UserAccount current = lockAccount(context);
        requireVersion(current, command.expectedVersion());
        requireSecurityVersion(current, command.expectedSecurityVersion());
        LocalCredentialMetadata expected = encoded.preparation().credential();
        LocalCredentialMetadata actual = requireCredential(current).metadata();
        if (actual.version() != expected.version()
                || !actual.credentialVersion().equals(expected.credentialVersion())) {
            throw new CurrentAccountMutationException(
                    CurrentAccountMutationFailure.CREDENTIAL_CONFLICT);
        }
        UtcTimestamp now = timeProvider.now();
        LocalCredentialMetadata replacement = actual.rotate(encoded.hash(), now);
        if (!credentials.rotateIfUnchanged(replacement, encoded.hash(), actual.version())) {
            throw new CurrentAccountMutationException(
                    CurrentAccountMutationFailure.CREDENTIAL_CONFLICT);
        }
        UserAccount committed = accounts.update(
                current.advanceSecurityVersion(now), current.version());
        return append(
                context,
                committed,
                "ACCOUNT_PASSWORD_CHANGED",
                new AccountPasswordChanged(
                        replacement.credentialVersion().value(),
                        committed.securityVersion().value()),
                now);
    }

    private CurrentAccountMutationResult commitSessionRevocation(
            CurrentAccountCommandContext context, AccountSessionRevocationCommand command) {
        UserAccount current = lockAccount(context);
        requireVersion(current, command.expectedVersion());
        requireSecurityVersion(current, command.expectedSecurityVersion());
        UtcTimestamp now = timeProvider.now();
        UserAccount committed = accounts.update(
                current.advanceSecurityVersion(now), current.version());
        return append(
                context,
                committed,
                "ACCOUNT_LOGGED_OUT",
                new AccountLoggedOut(
                        AccountLogoutScope.ALL_SESSIONS,
                        committed.securityVersion().value()),
                now);
    }

    private CompletionStage<Void> requireStepUp(
            UserAccount account,
            Optional<String> currentPassword,
            java.util.OptionalLong expectedSecurityVersion) {
        if (currentPassword.isEmpty() || expectedSecurityVersion.isEmpty()) {
            return CompletableFuture.failedFuture(new CurrentAccountMutationException(
                    CurrentAccountMutationFailure.INVALID_CURRENT_PASSWORD));
        }
        requireSecurityVersion(account, expectedSecurityVersion.orElseThrow());
        return verifyCurrentPassword(
                account, requireCredential(account), currentPassword.orElseThrow());
    }

    private CompletionStage<Void> verifyCurrentPassword(
            UserAccount account,
            LocalCredentialAuthenticationMaterial credential,
            String currentPassword) {
        return passwords.verify(currentPassword, Optional.of(credential), account.canAuthenticate())
                .thenCompose(verification -> verification.authenticated()
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(new CurrentAccountMutationException(
                                CurrentAccountMutationFailure.INVALID_CURRENT_PASSWORD)));
    }

    private UserAccount requireAccount(CurrentAccountCommandContext context) {
        return accounts.findById(context.accountId())
                .filter(UserAccount::canAuthenticate)
                .orElseThrow(() -> new CurrentAccountMutationException(
                        CurrentAccountMutationFailure.ACCOUNT_UNAVAILABLE));
    }

    private UserAccount lockAccount(CurrentAccountCommandContext context) {
        return accounts.findByIdForUpdate(context.accountId())
                .filter(UserAccount::canAuthenticate)
                .orElseThrow(() -> new CurrentAccountMutationException(
                        CurrentAccountMutationFailure.ACCOUNT_UNAVAILABLE));
    }

    private LocalCredentialAuthenticationMaterial requireCredential(UserAccount account) {
        return credentials.findByAccountIdForAuthentication(account.id())
                .filter(LocalCredentialAuthenticationMaterial::isUsable)
                .orElseThrow(() -> new CurrentAccountMutationException(
                        CurrentAccountMutationFailure.ACCOUNT_UNAVAILABLE));
    }

    private static void requireVersion(UserAccount account, long expectedVersion) {
        if (account.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "UserAccount", account.id(), expectedVersion, account.version());
        }
    }

    private static void requireSecurityVersion(UserAccount account, long expectedVersion) {
        if (account.securityVersion().value() != expectedVersion) {
            throw new CurrentAccountMutationException(
                    CurrentAccountMutationFailure.SECURITY_VERSION_CONFLICT);
        }
    }

    private <T extends DomainEvent> CurrentAccountMutationResult append(
            CurrentAccountCommandContext context,
            UserAccount account,
            String eventType,
            T payload,
            UtcTimestamp now) {
        DomainEventEnvelope<T> event = new DomainEventEnvelope<>(
                java.util.UUID.randomUUID(),
                EventType.from(eventType),
                SchemaVersion.V1,
                context.organizationId(),
                Optional.empty(),
                Optional.empty(),
                AggregateReference.of(ACCOUNT_AGGREGATE, account.id()),
                account.version(),
                EventActor.principal(EventActorType.USER, context.actorPrincipalId()),
                context.correlationId(),
                context.causationId(),
                Optional.empty(),
                now,
                payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(java.util.UUID.randomUUID(), event));
        return new CurrentAccountMutationResult(account, event.eventId());
    }

    private record PasswordChangePreparation(LocalCredentialMetadata credential) {
        private PasswordChangePreparation {
            credential = Objects.requireNonNull(credential, "credential");
        }
    }

    private record EncodedPasswordChange(
            PasswordChangePreparation preparation, LocalPasswordHash hash) {
        private EncodedPasswordChange {
            preparation = Objects.requireNonNull(preparation, "preparation");
            hash = Objects.requireNonNull(hash, "hash");
        }
    }
}
