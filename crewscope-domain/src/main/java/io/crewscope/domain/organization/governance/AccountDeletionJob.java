package io.crewscope.domain.organization.governance;

import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/**
 * One account-scoped deletion (S01 freeze 3.4): create pins the precondition snapshot and the
 * coordination checklist with live expected counts; confirm echoes the target username as the
 * second, deliberate confirmation step; process settles each step in its own short transaction.
 * account_id and target_principal_id are bare facts of the rows being removed — they must
 * outlive the foreign keys they name (the terminal step deletes those rows).
 */
public final class AccountDeletionJob {

    private final AccountDeletionJobId id;
    private final OrganizationId organizationId;
    private final UserAccountId accountId;
    private final PrincipalId targetPrincipalId;
    private final String reason;
    private final String confirmationTokenHash;
    private final AccountDeletionJobStatus status;
    private final String commandId;
    private final String reservationCheck;
    private final String backupRetentionStatement;
    private final long version;
    private final AuditMetadata audit;

    private AccountDeletionJob(
            AccountDeletionJobId id,
            OrganizationId organizationId,
            UserAccountId accountId,
            PrincipalId targetPrincipalId,
            String reason,
            String confirmationTokenHash,
            AccountDeletionJobStatus status,
            String commandId,
            String reservationCheck,
            String backupRetentionStatement,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.targetPrincipalId = Objects.requireNonNull(targetPrincipalId, "targetPrincipalId");
        this.reason = requireReason(reason);
        this.confirmationTokenHash = requireTokenHash(confirmationTokenHash);
        this.status = Objects.requireNonNull(status, "status");
        this.commandId = requireCommandId(commandId);
        this.reservationCheck = requireJson(reservationCheck, "accountDeletionJob.reservationCheck");
        this.backupRetentionStatement = requireJson(
                backupRetentionStatement, "accountDeletionJob.backupRetentionStatement");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Queues a deletion that waits for the token confirmation before anything is touched. */
    public static AccountDeletionJob create(
            AccountDeletionJobId id,
            OrganizationId organizationId,
            UserAccountId accountId,
            PrincipalId targetPrincipalId,
            String reason,
            String confirmationTokenHash,
            String commandId,
            String reservationCheck,
            String backupRetentionStatement,
            PrincipalId createdByPrincipalId,
            UtcTimestamp occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return new AccountDeletionJob(
                id,
                organizationId,
                accountId,
                targetPrincipalId,
                reason,
                confirmationTokenHash,
                AccountDeletionJobStatus.PENDING_CONFIRMATION,
                commandId,
                reservationCheck,
                backupRetentionStatement,
                0,
                AuditMetadata.createdBy(createdByPrincipalId, occurredAt));
    }

    /** Reconstitutes a committed job without replaying lifecycle behavior. */
    public static AccountDeletionJob reconstitute(
            AccountDeletionJobId id,
            OrganizationId organizationId,
            UserAccountId accountId,
            PrincipalId targetPrincipalId,
            String reason,
            String confirmationTokenHash,
            AccountDeletionJobStatus status,
            String commandId,
            String reservationCheck,
            String backupRetentionStatement,
            long version,
            AuditMetadata audit) {
        return new AccountDeletionJob(
                id,
                organizationId,
                accountId,
                targetPrincipalId,
                reason,
                confirmationTokenHash,
                status,
                commandId,
                reservationCheck,
                backupRetentionStatement,
                version,
                audit);
    }

    /**
     * The second, deliberate confirmation: only the exact expected token hash moves the job to
     * CONFIRMED. A mismatch never mutates the job — the caller reports it as a validation error.
     */
    public boolean matchesConfirmationToken(String candidateHash) {
        return confirmationTokenHash.equals(
                requireTokenHash(Objects.requireNonNull(candidateHash, "candidateHash")));
    }

    /** Records the confirmed intent; only a PENDING_CONFIRMATION job can be confirmed. */
    public AccountDeletionJob markConfirmed(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != AccountDeletionJobStatus.PENDING_CONFIRMATION) {
            throw new InvalidStateTransitionException(
                    "AccountDeletionJob", id, status, AccountDeletionJobStatus.CONFIRMED);
        }
        return transitioned(AccountDeletionJobStatus.CONFIRMED, actor, occurredAt);
    }

    /** Claims a CONFIRMED job for processing; an interrupted RUNNING job may be reclaimed. */
    public AccountDeletionJob markRunning(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != AccountDeletionJobStatus.CONFIRMED
                && status != AccountDeletionJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "AccountDeletionJob", id, status, AccountDeletionJobStatus.RUNNING);
        }
        return transitioned(AccountDeletionJobStatus.RUNNING, actor, occurredAt);
    }

    /** Seals the job after every step reached a terminal state. */
    public AccountDeletionJob markCompleted(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != AccountDeletionJobStatus.CONFIRMED
                && status != AccountDeletionJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "AccountDeletionJob", id, status, AccountDeletionJobStatus.COMPLETED);
        }
        return transitioned(AccountDeletionJobStatus.COMPLETED, actor, occurredAt);
    }

    /** Cancels an unconfirmed or partially processed job; DONE steps are never rolled back. */
    public AccountDeletionJob markCancelled(PrincipalId actor, UtcTimestamp occurredAt) {
        if (isTerminal()) {
            throw new InvalidStateTransitionException(
                    "AccountDeletionJob", id, status, AccountDeletionJobStatus.CANCELLED);
        }
        return transitioned(AccountDeletionJobStatus.CANCELLED, actor, occurredAt);
    }

    public boolean isTerminal() {
        return status == AccountDeletionJobStatus.COMPLETED
                || status == AccountDeletionJobStatus.CANCELLED;
    }

    public AccountDeletionJobId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public UserAccountId accountId() {
        return accountId;
    }

    public PrincipalId targetPrincipalId() {
        return targetPrincipalId;
    }

    public String reason() {
        return reason;
    }

    public String confirmationTokenHash() {
        return confirmationTokenHash;
    }

    public AccountDeletionJobStatus status() {
        return status;
    }

    public String commandId() {
        return commandId;
    }

    public String reservationCheck() {
        return reservationCheck;
    }

    public String backupRetentionStatement() {
        return backupRetentionStatement;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private AccountDeletionJob transitioned(
            AccountDeletionJobStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        return new AccountDeletionJob(
                id,
                organizationId,
                accountId,
                targetPrincipalId,
                reason,
                confirmationTokenHash,
                target,
                commandId,
                reservationCheck,
                backupRetentionStatement,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static String requireReason(String reason) {
        String required = Objects.requireNonNull(reason, "reason");
        // The governance contract demands a deliberate, explainable reason (>= 20 characters).
        if (required.length() < 20 || required.length() > 500) {
            throw new DomainValidationException(
                    "accountDeletionJob.reason", "must be between 20 and 500 characters");
        }
        return required;
    }

    private static String requireTokenHash(String hash) {
        String required = Objects.requireNonNull(hash, "hash");
        if (required.length() != 64 || !required.matches("[0-9a-f]{64}")) {
            throw new DomainValidationException(
                    "accountDeletionJob.confirmationTokenHash", "must be a lowercase sha-256 hex");
        }
        return required;
    }

    private static String requireCommandId(String commandId) {
        String required = Objects.requireNonNull(commandId, "commandId");
        if (required.isBlank()) {
            throw new DomainValidationException(
                    "accountDeletionJob.commandId", "must not be blank");
        }
        return required;
    }

    private static String requireJson(String value, String field) {
        String required = Objects.requireNonNull(value, field.replaceFirst(".*\\.", ""));
        if (required.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "accountDeletionJob.version", "must not be negative");
        }
        return value;
    }
}
