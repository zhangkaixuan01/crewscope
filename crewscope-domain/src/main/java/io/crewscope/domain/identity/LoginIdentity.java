package io.crewscope.domain.identity;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Authentication-provider identity linked to one deployment-level user account. */
public final class LoginIdentity {

    private static final Map<LoginIdentityStatus, Set<LoginIdentityStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    LoginIdentityStatus.ACTIVE,
                    EnumSet.of(LoginIdentityStatus.DISABLED, LoginIdentityStatus.REVOKED),
                    LoginIdentityStatus.DISABLED,
                    EnumSet.of(LoginIdentityStatus.ACTIVE, LoginIdentityStatus.REVOKED),
                    LoginIdentityStatus.REVOKED,
                    EnumSet.noneOf(LoginIdentityStatus.class));

    private final LoginIdentityId id;
    private final UserAccountId accountId;
    private final IdentityProviderKey provider;
    private final LoginIdentitySubject subject;
    private final LoginIdentityStatus status;
    private final Optional<UtcTimestamp> lastAuthenticatedAt;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private LoginIdentity(
            LoginIdentityId id,
            UserAccountId accountId,
            IdentityProviderKey provider,
            LoginIdentitySubject subject,
            LoginIdentityStatus status,
            Optional<UtcTimestamp> lastAuthenticatedAt,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.subject = requireSubject(accountId, provider, subject);
        this.status = Objects.requireNonNull(status, "status");
        this.lastAuthenticatedAt = requireAuthenticationTime(lastAuthenticatedAt, lifecycle);
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Local subjects are always derived from the immutable account ID. */
    public static LoginIdentity local(
            LoginIdentityId id, UserAccountId accountId, UtcTimestamp occurredAt) {
        return create(
                id,
                accountId,
                IdentityProviderKey.local(),
                LoginIdentitySubject.local(accountId),
                occurredAt);
    }

    /** Links a future OIDC or social subject without using mutable profile claims. */
    public static LoginIdentity external(
            LoginIdentityId id,
            UserAccountId accountId,
            IdentityProviderKey provider,
            LoginIdentitySubject subject,
            UtcTimestamp occurredAt) {
        IdentityProviderKey requiredProvider = Objects.requireNonNull(provider, "provider");
        if (requiredProvider.isLocal()) {
            throw new DomainValidationException(
                    "loginIdentity.provider", "local identities must use the local factory");
        }
        return create(id, accountId, requiredProvider, subject, occurredAt);
    }

    /** Reconstitutes a committed identity while rechecking the local subject invariant. */
    public static LoginIdentity reconstitute(
            LoginIdentityId id,
            UserAccountId accountId,
            IdentityProviderKey provider,
            LoginIdentitySubject subject,
            LoginIdentityStatus status,
            Optional<UtcTimestamp> lastAuthenticatedAt,
            long version,
            LifecycleMetadata lifecycle) {
        return new LoginIdentity(
                id,
                accountId,
                provider,
                subject,
                status,
                lastAuthenticatedAt,
                version,
                lifecycle);
    }

    public LoginIdentity transitionTo(LoginIdentityStatus target, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("LoginIdentity", id, status, target);
        }
        LifecycleMetadata changedLifecycle = lifecycle.modifiedAt(occurredAt);
        return new LoginIdentity(
                id,
                accountId,
                provider,
                subject,
                target,
                lastAuthenticatedAt,
                nextVersion(),
                changedLifecycle);
    }

    /** Records only a successful authentication and never mutates provider or subject. */
    public LoginIdentity recordAuthentication(UtcTimestamp occurredAt) {
        if (!status.isUsable()) {
            throw new DomainValidationException(
                    "loginIdentity.status", "must be ACTIVE to authenticate");
        }
        UtcTimestamp authenticatedAt = Objects.requireNonNull(occurredAt, "occurredAt");
        lastAuthenticatedAt.ifPresent(previous -> {
            if (authenticatedAt.compareTo(previous) < 0) {
                throw new DomainValidationException(
                        "loginIdentity.lastAuthenticatedAt", "must not move backwards");
            }
        });
        LifecycleMetadata changedLifecycle = lifecycle.modifiedAt(authenticatedAt);
        return new LoginIdentity(
                id,
                accountId,
                provider,
                subject,
                status,
                Optional.of(authenticatedAt),
                nextVersion(),
                changedLifecycle);
    }

    public boolean isUsable() {
        return status.isUsable();
    }

    public LoginIdentityKey identityKey() {
        return new LoginIdentityKey(provider, subject);
    }

    public AccountIdentityProviderKey accountProviderKey() {
        return new AccountIdentityProviderKey(accountId, provider);
    }

    public LoginIdentityId id() {
        return id;
    }

    public UserAccountId accountId() {
        return accountId;
    }

    public IdentityProviderKey provider() {
        return provider;
    }

    public LoginIdentitySubject subject() {
        return subject;
    }

    public LoginIdentityStatus status() {
        return status;
    }

    public Optional<UtcTimestamp> lastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private static LoginIdentity create(
            LoginIdentityId id,
            UserAccountId accountId,
            IdentityProviderKey provider,
            LoginIdentitySubject subject,
            UtcTimestamp occurredAt) {
        return new LoginIdentity(
                id,
                accountId,
                provider,
                subject,
                LoginIdentityStatus.ACTIVE,
                Optional.empty(),
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    private static LoginIdentitySubject requireSubject(
            UserAccountId accountId,
            IdentityProviderKey provider,
            LoginIdentitySubject subject) {
        LoginIdentitySubject requiredSubject = Objects.requireNonNull(subject, "subject");
        if (provider.isLocal() && !requiredSubject.equals(LoginIdentitySubject.local(accountId))) {
            throw new DomainValidationException(
                    "loginIdentity.subject", "local subject must equal the immutable Account ID");
        }
        return requiredSubject;
    }

    private static Optional<UtcTimestamp> requireAuthenticationTime(
            Optional<UtcTimestamp> value, LifecycleMetadata lifecycle) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(value, "lastAuthenticatedAt");
        LifecycleMetadata requiredLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        required.ifPresent(authenticatedAt -> {
            if (authenticatedAt.compareTo(requiredLifecycle.createdAt()) < 0
                    || authenticatedAt.compareTo(requiredLifecycle.updatedAt()) > 0) {
                throw new DomainValidationException(
                        "loginIdentity.lastAuthenticatedAt",
                        "must remain inside the identity lifecycle");
            }
        });
        return required;
    }

    private long nextVersion() {
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException("loginIdentity.version", "must not overflow");
        }
        return version + 1;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("loginIdentity.version", "must not be negative");
        }
        return value;
    }
}
