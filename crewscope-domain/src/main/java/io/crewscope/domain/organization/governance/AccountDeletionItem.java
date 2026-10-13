package io.crewscope.domain.organization.governance;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * One coordination step of the deletion checklist, pinned with the live expected count observed
 * at job creation. CONFLICT and DENIED keep their reason: a restored backup re-run converges by
 * settling already-deleted steps as no-op DONE or CONFLICT instead of rolling anything back.
 */
public final class AccountDeletionItem {

    private final AccountDeletionItemId id;
    private final OrganizationId organizationId;
    private final AccountDeletionJobId jobId;
    private final String stepKey;
    private final String targetRef;
    private final int expectedCount;
    private final AccountDeletionItemState state;
    private final Optional<Integer> resultCount;
    private final Optional<String> errorCode;
    private final Optional<UtcTimestamp> processedAt;
    private final long version;
    private final AuditMetadata audit;

    private AccountDeletionItem(
            AccountDeletionItemId id,
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            String stepKey,
            String targetRef,
            int expectedCount,
            AccountDeletionItemState state,
            Optional<Integer> resultCount,
            Optional<String> errorCode,
            Optional<UtcTimestamp> processedAt,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.stepKey = requireStepKey(stepKey);
        this.targetRef = requireTargetRef(targetRef);
        this.expectedCount = requireExpectedCount(expectedCount);
        this.state = Objects.requireNonNull(state, "state");
        this.resultCount = requireResultCount(state, resultCount);
        this.errorCode = requireErrorCode(state, errorCode);
        this.processedAt = requireProcessedAt(state, processedAt);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Queues one coordination step against the exact row set observed at creation. */
    public static AccountDeletionItem create(
            AccountDeletionItemId id,
            AccountDeletionJobId jobId,
            OrganizationId organizationId,
            String stepKey,
            String targetRef,
            int expectedCount,
            PrincipalId createdByPrincipalId,
            UtcTimestamp occurredAt) {
        return new AccountDeletionItem(
                id,
                organizationId,
                jobId,
                stepKey,
                targetRef,
                expectedCount,
                AccountDeletionItemState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(createdByPrincipalId, occurredAt));
    }

    /** Reconstitutes a committed item without replaying lifecycle behavior. */
    public static AccountDeletionItem reconstitute(
            AccountDeletionItemId id,
            OrganizationId organizationId,
            AccountDeletionJobId jobId,
            String stepKey,
            String targetRef,
            int expectedCount,
            AccountDeletionItemState state,
            Optional<Integer> resultCount,
            Optional<String> errorCode,
            Optional<UtcTimestamp> processedAt,
            long version,
            AuditMetadata audit) {
        return new AccountDeletionItem(
                id,
                organizationId,
                jobId,
                stepKey,
                targetRef,
                expectedCount,
                state,
                resultCount,
                errorCode,
                processedAt,
                version,
                audit);
    }

    /** Records a committed step with the exact number of affected rows. */
    public AccountDeletionItem markDone(int resultCount, PrincipalId actor, UtcTimestamp occurredAt) {
        if (resultCount < 0) {
            throw new DomainValidationException(
                    "accountDeletionItem.resultCount", "must not be negative");
        }
        return transitioned(
                AccountDeletionItemState.DONE,
                Optional.of(resultCount),
                Optional.empty(),
                actor,
                occurredAt);
    }

    /** Records a stale-view stop: the pinned count no longer matches the live rows. */
    public AccountDeletionItem markConflict(String code, PrincipalId actor, UtcTimestamp occurredAt) {
        return transitioned(
                AccountDeletionItemState.CONFLICT,
                Optional.empty(),
                Optional.of(requireCode(code)),
                actor,
                occurredAt);
    }

    /** Records a policy stop: the step is not allowed to run against the current facts. */
    public AccountDeletionItem markDenied(String code, PrincipalId actor, UtcTimestamp occurredAt) {
        return transitioned(
                AccountDeletionItemState.DENIED,
                Optional.empty(),
                Optional.of(requireCode(code)),
                actor,
                occurredAt);
    }

    public boolean isPending() {
        return state == AccountDeletionItemState.PENDING;
    }

    public AccountDeletionItemId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public AccountDeletionJobId jobId() {
        return jobId;
    }

    public String stepKey() {
        return stepKey;
    }

    public String targetRef() {
        return targetRef;
    }

    public int expectedCount() {
        return expectedCount;
    }

    public AccountDeletionItemState state() {
        return state;
    }

    public Optional<Integer> resultCount() {
        return resultCount;
    }

    public Optional<String> errorCode() {
        return errorCode;
    }

    public Optional<UtcTimestamp> processedAt() {
        return processedAt;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private AccountDeletionItem transitioned(
            AccountDeletionItemState target,
            Optional<Integer> result,
            Optional<String> code,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (state != AccountDeletionItemState.PENDING) {
            throw new InvalidStateTransitionException(
                    "AccountDeletionItem", id, state, target);
        }
        return new AccountDeletionItem(
                id,
                organizationId,
                jobId,
                stepKey,
                targetRef,
                expectedCount,
                target,
                result,
                code,
                Optional.of(occurredAt),
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static String requireStepKey(String stepKey) {
        String required = Objects.requireNonNull(stepKey, "stepKey");
        if (required.isBlank()) {
            throw new DomainValidationException("accountDeletionItem.stepKey", "must not be blank");
        }
        return required;
    }

    private static String requireTargetRef(String targetRef) {
        String required = Objects.requireNonNull(targetRef, "targetRef");
        if (required.isBlank()) {
            throw new DomainValidationException(
                    "accountDeletionItem.targetRef", "must not be blank");
        }
        return required;
    }

    private static int requireExpectedCount(int value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "accountDeletionItem.expectedCount", "must not be negative");
        }
        return value;
    }

    private static String requireCode(String code) {
        String required = Objects.requireNonNull(code, "code");
        if (required.isBlank()) {
            throw new DomainValidationException(
                    "accountDeletionItem.errorCode", "must not be blank");
        }
        return required;
    }

    private static Optional<Integer> requireResultCount(
            AccountDeletionItemState state, Optional<Integer> resultCount) {
        Optional<Integer> required = Objects.requireNonNull(resultCount, "resultCount");
        // Only DONE names the affected row count; stopped steps never do.
        if ((state == AccountDeletionItemState.DONE) != required.isPresent()) {
            throw new DomainValidationException(
                    "accountDeletionItem.resultCount",
                    state == AccountDeletionItemState.DONE
                            ? "is required for a done item"
                            : "is only allowed for a done item");
        }
        return required;
    }

    private static Optional<String> requireErrorCode(
            AccountDeletionItemState state, Optional<String> errorCode) {
        Optional<String> required = Objects.requireNonNull(errorCode, "errorCode");
        // CONFLICT and DENIED must explain themselves; DONE and PENDING carry no code.
        boolean stopped =
                state == AccountDeletionItemState.CONFLICT
                        || state == AccountDeletionItemState.DENIED;
        if (stopped == required.isEmpty()) {
            throw new DomainValidationException(
                    "accountDeletionItem.errorCode",
                    stopped ? "is required for a stopped item" : "is only allowed for a stopped item");
        }
        return required;
    }

    private static Optional<UtcTimestamp> requireProcessedAt(
            AccountDeletionItemState state, Optional<UtcTimestamp> processedAt) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(processedAt, "processedAt");
        if ((state != AccountDeletionItemState.PENDING) != required.isPresent()) {
            throw new DomainValidationException(
                    "accountDeletionItem.processedAt",
                    state != AccountDeletionItemState.PENDING
                            ? "is required for a processed item"
                            : "is only allowed for a processed item");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "accountDeletionItem.version", "must not be negative");
        }
        return value;
    }
}
