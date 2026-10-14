package io.crewscope.application.identity;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.ExternalAccountIdentity;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityConflictException;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.PlatformRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.identity.Username;
import io.crewscope.domain.identity.event.AccountRegistrationSource;
import io.crewscope.domain.identity.event.AuthenticationSucceeded;
import io.crewscope.domain.identity.event.UserAccountRegistered;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Federated login use-case (M12-I01): one locked-lookup transaction either re-authenticates a
 * linked identity or provisions the first-login account set. The (provider, subject) row is the
 * idempotency fact — concurrent first logins serialize on the row lock, and the unique index is
 * the final arbiter — so no command receipt is consumed. Defense locking is observed for known
 * accounts and a refused login records a failure without touching any session.
 */
public final class OidcAccountAuthenticationService {

    private static final String ACCOUNT_AGGREGATE = "ACCOUNT";

    private final LoginIdentityRepository loginIdentities;
    private final UserAccountRepository accounts;
    private final PrincipalRepository principals;
    private final AccountOrganizationBindingRepository bindings;
    private final InitialOrganizationOwnerProvisioner initialOwner;
    private final AuthenticatedAccountOrganizationResolver resolver;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final TransactionExecutor transactions;
    private final LoginDefense defense;
    private final TimeProvider timeProvider;
    private final Executor persistenceExecutor;

    public OidcAccountAuthenticationService(
            LoginIdentityRepository loginIdentities,
            UserAccountRepository accounts,
            PrincipalRepository principals,
            AccountOrganizationBindingRepository bindings,
            InitialOrganizationOwnerProvisioner initialOwner,
            AuthenticatedAccountOrganizationResolver resolver,
            DomainEventStore events,
            OutboxRepository outbox,
            TransactionExecutor transactions,
            LoginDefense defense,
            TimeProvider timeProvider,
            Executor persistenceExecutor) {
        this.loginIdentities = Objects.requireNonNull(loginIdentities, "loginIdentities");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.initialOwner = Objects.requireNonNull(initialOwner, "initialOwner");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.defense = Objects.requireNonNull(defense, "defense");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
    }

    /** Runs one locked lookup; a hit re-authenticates, a miss provisions under the mode gate. */
    public CompletionStage<OidcAuthenticationResult> authenticate(OidcAuthenticationCommand command) {
        OidcAuthenticationCommand trusted = Objects.requireNonNull(command, "command");
        return CompletableFuture.supplyAsync(
                () -> authenticateAllAttempts(trusted), persistenceExecutor);
    }

    /**
     * Each attempt owns its transaction: a unique-index collision aborts the PostgreSQL
     * transaction, so the retry must start a fresh one whose locked lookup can then observe the
     * concurrent winner's committed row and converge on re-authentication.
     */
    private OidcAuthenticationResult authenticateAllAttempts(OidcAuthenticationCommand command) {
        for (int attempt = 0;
                attempt <= ExternalAccountIdentity.maximumDerivationAttempts();
                attempt++) {
            int derivation = attempt;
            try {
                return transactions.required(() -> authenticateOnce(command, derivation));
            } catch (AccountIdentifierConflictException | LoginIdentityConflictException conflict) {
                // A deterministic derivation or the concurrent winner's (provider, subject)
                // row collided; the fresh transaction's locked lookup decides the next attempt.
            }
        }
        // Deterministic derivations are exhausted: fail closed instead of inventing entropy.
        throw new OidcAuthenticationException(OidcAuthenticationException.Failure.UNAVAILABLE);
    }

    private OidcAuthenticationResult authenticateOnce(OidcAuthenticationCommand command, int attempt) {
        UtcTimestamp now = timeProvider.now();
        ExternalAccountIdentity identity = command.identity();
        Optional<LoginIdentity> linked =
                loginIdentities.findByIdentityKeyForUpdate(identity.identityKey());
        if (linked.isPresent()) {
            return reauthenticate(linked.orElseThrow(), command, now);
        }
        if (!command.registrationMode().allowsRegistration(false)) {
            throw new OidcAuthenticationException(
                    OidcAuthenticationException.Failure.REGISTRATION_BLOCKED);
        }
        return provision(command, identity.username(attempt), now);
    }

    private OidcAuthenticationResult reauthenticate(
            LoginIdentity identity, OidcAuthenticationCommand command, UtcTimestamp now) {
        UserAccountId accountId = identity.accountId();
        AccountLoginDefenseState state = join(defense.observeAccount(accountId));
        if (state.temporarilyLocked()) {
            join(defense.recordFailure(accountId));
            throw new OidcAuthenticationException(OidcAuthenticationException.Failure.LOCKED);
        }
        AccountOrganizationResolution resolution;
        try {
            resolution = resolver
                    .resolveExternal(identity.identityKey(), command.organizationId())
                    .orElseThrow(() -> new OidcAuthenticationException(
                            OidcAuthenticationException.Failure.DENIED));
        } catch (PolicyDeniedException denied) {
            throw new OidcAuthenticationException(OidcAuthenticationException.Failure.DENIED);
        }
        loginIdentities.update(identity.recordAuthentication(now), identity.version());
        return new OidcAuthenticationResult(resolution, false);
    }

    private OidcAuthenticationResult provision(
            OidcAuthenticationCommand command, Username username, UtcTimestamp now) {
        ExternalAccountIdentity identity = command.identity();
        UserAccount account = accounts.create(UserAccount.register(
                UserAccountId.generate(),
                username.displayValue(),
                identity.email(username),
                identity.displayName(),
                now));
        // The external factory stamps the first authentication time; recordAuthentication is
        // reserved for re-authentications, which arrive as versioned updates.
        LoginIdentity loginIdentity = loginIdentities.create(
                LoginIdentity.external(
                        LoginIdentityId.generate(),
                        account.id(),
                        identity.provider(),
                        new LoginIdentitySubject(identity.subject()),
                        now));
        Principal principal = principals.createLocalUser(Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(command.organizationId()),
                PrincipalType.USER,
                Optional.empty(),
                account.displayName(),
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                now));
        AccountOrganizationBinding binding = bindings.create(AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                command.organizationId(),
                principal,
                now));
        // A federated first login can be the organization's only entry point; the provisioning
        // is a no-op when an owner already exists.
        initialOwner.ensureInitialOwner(command.organizationId(), principal.id());
        appendRegistered(command, account, principal, now);
        AccountOrganizationResolution resolution = new AccountOrganizationResolution(
                account, loginIdentity, binding, principal);
        return new OidcAuthenticationResult(resolution, true);
    }

    private void appendRegistered(
            OidcAuthenticationCommand command,
            UserAccount account,
            Principal principal,
            UtcTimestamp now) {
        append(envelope(
                UUID.randomUUID(),
                "USER_ACCOUNT_REGISTERED",
                AggregateReference.of(ACCOUNT_AGGREGATE, account.id()),
                account.version(),
                command,
                principal,
                now,
                new UserAccountRegistered(AccountRegistrationSource.SSO, PlatformRole.USER)));
        append(envelope(
                UUID.randomUUID(),
                "AUTHENTICATION_SUCCEEDED",
                AggregateReference.of(ACCOUNT_AGGREGATE, account.id()),
                account.version(),
                command,
                principal,
                now,
                new AuthenticationSucceeded(
                        command.identity().provider().value(), false,
                        account.securityVersion().value())));
    }

    private <E extends DomainEvent> DomainEventEnvelope<E> envelope(
            UUID eventId,
            String typeName,
            AggregateReference aggregate,
            long version,
            OidcAuthenticationCommand command,
            Principal principal,
            UtcTimestamp now,
            E payload) {
        return new DomainEventEnvelope<>(
                eventId,
                EventType.from(typeName),
                SchemaVersion.V1,
                command.organizationId(),
                Optional.empty(),
                Optional.empty(),
                aggregate,
                version,
                EventActor.principal(EventActorType.USER, principal.id()),
                command.correlationId(),
                command.causationId(),
                Optional.empty(),
                now,
                payload);
    }

    private void append(DomainEventEnvelope<?> event) {
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
    }

    private static <T> T join(CompletionStage<T> stage) {
        CompletableFuture<T> future = stage.toCompletableFuture();
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }
}
