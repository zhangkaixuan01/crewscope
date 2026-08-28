package io.crewscope.domain.identity;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One-way authorization bridge from a deployment Account to an Organization USER Principal. */
public final class AccountOrganizationBinding {

    private static final Map<AccountOrganizationBindingStatus, Set<AccountOrganizationBindingStatus>>
            ALLOWED_TRANSITIONS = Map.of(
                    AccountOrganizationBindingStatus.ACTIVE,
                    EnumSet.of(AccountOrganizationBindingStatus.DISABLED),
                    AccountOrganizationBindingStatus.DISABLED,
                    EnumSet.of(AccountOrganizationBindingStatus.ACTIVE));

    private final AccountOrganizationBindingId id;
    private final UserAccountId accountId;
    private final OrganizationId organizationId;
    private final PrincipalId principalId;
    private final AccountOrganizationBindingStatus status;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private AccountOrganizationBinding(
            AccountOrganizationBindingId id,
            UserAccountId accountId,
            OrganizationId organizationId,
            PrincipalId principalId,
            AccountOrganizationBindingStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Creates an active binding after checking both ends are currently allowed to act. */
    public static AccountOrganizationBinding bind(
            AccountOrganizationBindingId id,
            UserAccount account,
            OrganizationId organizationId,
            Principal principal,
            UtcTimestamp occurredAt) {
        UserAccount usableAccount = requireUsableAccount(account);
        Principal usablePrincipal = requireUsablePrincipal(principal, organizationId);
        return new AccountOrganizationBinding(
                id,
                usableAccount.id(),
                organizationId,
                usablePrincipal.id(),
                AccountOrganizationBindingStatus.ACTIVE,
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    /** Reconstitutes a persisted row; request resolution rechecks both referenced aggregates. */
    public static AccountOrganizationBinding reconstitute(
            AccountOrganizationBindingId id,
            UserAccountId accountId,
            OrganizationId organizationId,
            PrincipalId principalId,
            AccountOrganizationBindingStatus status,
            long version,
            LifecycleMetadata lifecycle) {
        return new AccountOrganizationBinding(
                id, accountId, organizationId, principalId, status, version, lifecycle);
    }

    public AccountOrganizationBinding disable(UtcTimestamp occurredAt) {
        return transitionTo(AccountOrganizationBindingStatus.DISABLED, occurredAt);
    }

    /** Reactivates access only after rechecking the immutable Account and Principal ends. */
    public AccountOrganizationBinding activate(
            UserAccount account, Principal principal, UtcTimestamp occurredAt) {
        UserAccount usableAccount = requireUsableAccount(account);
        Principal usablePrincipal = requireUsablePrincipal(principal, organizationId);
        if (!accountId.equals(usableAccount.id())) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.accountId", "must remain unchanged");
        }
        if (!principalId.equals(usablePrincipal.id())) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.principalId", "must remain unchanged");
        }
        return transitionTo(AccountOrganizationBindingStatus.ACTIVE, occurredAt);
    }

    public boolean isUsable() {
        return status.isUsable();
    }

    /** Rechecks the Principal type, scope, Organization and state used by request resolution. */
    public boolean isCompatibleWith(Principal principal) {
        if (principal == null) {
            return false;
        }
        return principalId.equals(principal.id())
                && principal.type() == PrincipalType.USER
                && principal.scope().organizationId().equals(organizationId)
                && principal.scope().teamId().isEmpty()
                && principal.canAct();
    }

    public AccountOrganizationKey accountOrganizationKey() {
        return new AccountOrganizationKey(accountId, organizationId);
    }

    public OrganizationPrincipalKey organizationPrincipalKey() {
        return new OrganizationPrincipalKey(organizationId, principalId);
    }

    public AccountOrganizationBindingId id() {
        return id;
    }

    public UserAccountId accountId() {
        return accountId;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public PrincipalId principalId() {
        return principalId;
    }

    public AccountOrganizationBindingStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private AccountOrganizationBinding transitionTo(
            AccountOrganizationBindingStatus target, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException(
                    "AccountOrganizationBinding", id, status, target);
        }
        return new AccountOrganizationBinding(
                id,
                accountId,
                organizationId,
                principalId,
                target,
                nextVersion(),
                lifecycle.modifiedAt(occurredAt));
    }

    private static UserAccount requireUsableAccount(UserAccount account) {
        UserAccount required = Objects.requireNonNull(account, "account");
        if (!required.canAuthenticate()) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.account", "must be ACTIVE");
        }
        return required;
    }

    private static Principal requireUsablePrincipal(
            Principal principal, OrganizationId organizationId) {
        Principal required = Objects.requireNonNull(principal, "principal");
        OrganizationId requiredOrganization =
                Objects.requireNonNull(organizationId, "organizationId");
        if (required.type() != PrincipalType.USER) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.principal", "must be a USER Principal");
        }
        if (!required.scope().organizationId().equals(requiredOrganization)
                || required.scope().teamId().isPresent()) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.principal",
                    "must use Organization scope in the binding Organization");
        }
        if (!required.canAct()) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.principal", "must be ACTIVE");
        }
        return required;
    }

    private long nextVersion() {
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.version", "must not overflow");
        }
        return version + 1;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "accountOrganizationBinding.version", "must not be negative");
        }
        return value;
    }
}
