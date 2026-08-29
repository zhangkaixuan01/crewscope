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

/** Deployment-level login aggregate, independent from Organization Principal and TeamMember. */
public final class UserAccount {

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;

    private static final Map<AccountStatus, Set<AccountStatus>> ALLOWED_TRANSITIONS = Map.of(
            AccountStatus.ACTIVE,
            EnumSet.of(AccountStatus.LOCKED, AccountStatus.DISABLED, AccountStatus.ARCHIVED),
            AccountStatus.LOCKED,
            EnumSet.of(AccountStatus.ACTIVE, AccountStatus.DISABLED, AccountStatus.ARCHIVED),
            AccountStatus.DISABLED,
            EnumSet.of(AccountStatus.ACTIVE, AccountStatus.ARCHIVED),
            AccountStatus.ARCHIVED,
            EnumSet.noneOf(AccountStatus.class));

    private final UserAccountId id;
    private final Username username;
    private final String email;
    private final NormalizedEmail normalizedEmail;
    private final String displayName;
    private final AccountStatus status;
    private final PlatformRole platformRole;
    private final SecurityVersion securityVersion;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private UserAccount(
            UserAccountId id,
            Username username,
            String email,
            NormalizedEmail normalizedEmail,
            String displayName,
            AccountStatus status,
            PlatformRole platformRole,
            SecurityVersion securityVersion,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.username = Objects.requireNonNull(username, "username");
        this.email = requireEmailDisplay(email, normalizedEmail);
        this.normalizedEmail = Objects.requireNonNull(normalizedEmail, "normalizedEmail");
        this.displayName = AccountTextPolicy.displayText(
                displayName, "userAccount.displayName", 1, MAX_DISPLAY_NAME_LENGTH);
        this.status = Objects.requireNonNull(status, "status");
        this.platformRole = Objects.requireNonNull(platformRole, "platformRole");
        this.securityVersion = Objects.requireNonNull(securityVersion, "securityVersion");
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Creates an ordinary self-service account; callers cannot inject a platform role. */
    public static UserAccount register(
            UserAccountId id,
            String username,
            String email,
            String displayName,
            UtcTimestamp occurredAt) {
        return create(id, username, email, displayName, PlatformRole.USER, occurredAt);
    }

    /** Creates the deployment bootstrap operator through a deliberately separate trusted path. */
    public static UserAccount bootstrapOperator(
            UserAccountId id,
            String username,
            String email,
            String displayName,
            UtcTimestamp occurredAt) {
        return create(id, username, email, displayName, PlatformRole.OPERATOR, occurredAt);
    }

    /** Reconstitutes a committed account without replaying profile or security changes. */
    public static UserAccount reconstitute(
            UserAccountId id,
            Username username,
            String email,
            NormalizedEmail normalizedEmail,
            String displayName,
            AccountStatus status,
            PlatformRole platformRole,
            SecurityVersion securityVersion,
            long version,
            LifecycleMetadata lifecycle) {
        return new UserAccount(
                id,
                username,
                email,
                normalizedEmail,
                displayName,
                status,
                platformRole,
                securityVersion,
                version,
                lifecycle);
    }

    /** Changes both username representations atomically while preserving the submitted display form. */
    public UserAccount changeUsername(String targetUsername, UtcTimestamp occurredAt) {
        return changeProfile(
                Optional.of(targetUsername), Optional.empty(), Optional.empty(), occurredAt);
    }

    /** Changes the display email and its normalized unique key in one aggregate version. */
    public UserAccount changeEmail(String targetEmail, UtcTimestamp occurredAt) {
        return changeProfile(
                Optional.empty(), Optional.of(targetEmail), Optional.empty(), occurredAt);
    }

    public UserAccount changeDisplayName(String targetDisplayName, UtcTimestamp occurredAt) {
        return changeProfile(
                Optional.empty(), Optional.empty(), Optional.of(targetDisplayName), occurredAt);
    }

    /** Applies one profile form as one aggregate revision, even when several fields change. */
    public UserAccount changeProfile(
            Optional<String> targetUsername,
            Optional<String> targetEmail,
            Optional<String> targetDisplayName,
            UtcTimestamp occurredAt) {
        requireMutable();
        Optional<String> requestedUsername = Objects.requireNonNull(targetUsername, "targetUsername");
        Optional<String> requestedEmail = Objects.requireNonNull(targetEmail, "targetEmail");
        Optional<String> requestedDisplayName =
                Objects.requireNonNull(targetDisplayName, "targetDisplayName");
        if (requestedUsername.isEmpty()
                && requestedEmail.isEmpty()
                && requestedDisplayName.isEmpty()) {
            throw new DomainValidationException(
                    "userAccount.profile", "must contain at least one field");
        }
        Username changedUsername = requestedUsername.map(Username::new).orElse(username);
        String changedEmail = requestedEmail.map(UserAccount::emailDisplay).orElse(email);
        NormalizedEmail changedNormalizedEmail = requestedEmail
                .map(ignored -> NormalizedEmail.fromDisplayValue(changedEmail))
                .orElse(normalizedEmail);
        String changedDisplayName = requestedDisplayName
                .map(value -> AccountTextPolicy.displayText(
                        value, "userAccount.displayName", 1, MAX_DISPLAY_NAME_LENGTH))
                .orElse(displayName);
        if (changedUsername.equals(username)
                && changedEmail.equals(email)
                && changedDisplayName.equals(displayName)) {
            throw new DomainValidationException(
                    "userAccount.profile", "must change at least one field");
        }
        return copy(
                changedUsername,
                changedEmail,
                changedNormalizedEmail,
                changedDisplayName,
                status,
                securityVersion,
                nextVersion(),
                modifiedAt(occurredAt));
    }

    /** Applies a closed account-state transition and revokes sessions through SecurityVersion. */
    public UserAccount transitionTo(AccountStatus target, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("UserAccount", id, status, target);
        }
        return copy(
                username,
                email,
                normalizedEmail,
                displayName,
                target,
                securityVersion.next(),
                nextVersion(),
                modifiedAt(occurredAt));
    }

    /** Invalidates existing sessions after a password or all-session revocation command. */
    public UserAccount advanceSecurityVersion(UtcTimestamp occurredAt) {
        requireMutable();
        return copy(
                username,
                email,
                normalizedEmail,
                displayName,
                status,
                securityVersion.next(),
                nextVersion(),
                modifiedAt(occurredAt));
    }

    public boolean canAuthenticate() {
        return status.canAuthenticate();
    }

    public boolean allowsPlatformOperations() {
        return canAuthenticate() && platformRole.allowsPlatformOperations();
    }

    public UserAccountId id() {
        return id;
    }

    public Username username() {
        return username;
    }

    public String email() {
        return email;
    }

    public NormalizedEmail normalizedEmail() {
        return normalizedEmail;
    }

    public String displayName() {
        return displayName;
    }

    public AccountStatus status() {
        return status;
    }

    public PlatformRole platformRole() {
        return platformRole;
    }

    public SecurityVersion securityVersion() {
        return securityVersion;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    private static UserAccount create(
            UserAccountId id,
            String username,
            String email,
            String displayName,
            PlatformRole role,
            UtcTimestamp occurredAt) {
        String safeEmail = emailDisplay(email);
        return new UserAccount(
                id,
                new Username(username),
                safeEmail,
                NormalizedEmail.fromDisplayValue(safeEmail),
                displayName,
                AccountStatus.ACTIVE,
                role,
                SecurityVersion.initial(),
                0,
                LifecycleMetadata.createdAt(occurredAt));
    }

    private UserAccount copy(
            Username targetUsername,
            String targetEmail,
            NormalizedEmail targetNormalizedEmail,
            String targetDisplayName,
            AccountStatus targetStatus,
            SecurityVersion targetSecurityVersion,
            long targetVersion,
            LifecycleMetadata targetLifecycle) {
        return new UserAccount(
                id,
                targetUsername,
                targetEmail,
                targetNormalizedEmail,
                targetDisplayName,
                targetStatus,
                platformRole,
                targetSecurityVersion,
                targetVersion,
                targetLifecycle);
    }

    private void requireMutable() {
        if (status.isTerminal()) {
            throw new InvalidStateTransitionException(
                    "UserAccount", id, AccountStatus.ARCHIVED, AccountStatus.ARCHIVED);
        }
    }

    private LifecycleMetadata modifiedAt(UtcTimestamp occurredAt) {
        return lifecycle.modifiedAt(occurredAt);
    }

    private long nextVersion() {
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException("userAccount.version", "must not overflow");
        }
        return version + 1;
    }

    private static String requireEmailDisplay(String email, NormalizedEmail normalizedEmail) {
        String safeEmail = emailDisplay(email);
        NormalizedEmail derived = NormalizedEmail.fromDisplayValue(safeEmail);
        if (!derived.equals(Objects.requireNonNull(normalizedEmail, "normalizedEmail"))) {
            throw new DomainValidationException(
                    "userAccount.normalizedEmail", "must match the display email");
        }
        return safeEmail;
    }

    private static String emailDisplay(String value) {
        return AccountTextPolicy.displayText(
                value, "userAccount.email", 3, NormalizedEmail.MAX_LENGTH);
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("userAccount.version", "must not be negative");
        }
        return value;
    }
}
