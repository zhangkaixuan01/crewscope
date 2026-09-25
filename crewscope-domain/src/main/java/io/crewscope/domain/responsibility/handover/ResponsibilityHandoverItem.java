package io.crewscope.domain.responsibility.handover;

import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/**
 * One queued transfer step: the source assignment (pinned with its expected version for the
 * ABA check) of the job's role and WorkItem scope. A terminal state records the outcome of
 * replaying the ordinary responsibility commands in one short transaction.
 */
public final class ResponsibilityHandoverItem {

    private final ResponsibilityHandoverItemId id;
    private final OrganizationId organizationId;
    private final ResponsibilityHandoverJobId jobId;
    private final ResponsibilityAssignmentId assignmentId;
    private final WorkItemId workItemId;
    private final WorkItemScope scope;
    private final long expectedAssignmentVersion;
    private final HandoverItemState state;
    private final Optional<ResponsibilityAssignmentId> resultAssignmentId;
    private final Optional<String> errorCode;
    private final Optional<UtcTimestamp> processedAt;
    private final long version;
    private final AuditMetadata audit;

    private ResponsibilityHandoverItem(
            ResponsibilityHandoverItemId id,
            OrganizationId organizationId,
            ResponsibilityHandoverJobId jobId,
            ResponsibilityAssignmentId assignmentId,
            WorkItemId workItemId,
            WorkItemScope scope,
            long expectedAssignmentVersion,
            HandoverItemState state,
            Optional<ResponsibilityAssignmentId> resultAssignmentId,
            Optional<String> errorCode,
            Optional<UtcTimestamp> processedAt,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.jobId = Objects.requireNonNull(jobId, "jobId");
        this.assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.expectedAssignmentVersion = requireExpectedVersion(expectedAssignmentVersion);
        this.state = Objects.requireNonNull(state, "state");
        this.resultAssignmentId = requireResult(state, resultAssignmentId);
        this.errorCode = requireErrorCode(state, errorCode);
        this.processedAt = requireProcessedAt(state, processedAt);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Queues the source assignment with the exact version the handover commands must expect. */
    public static ResponsibilityHandoverItem create(
            ResponsibilityHandoverItemId id,
            ResponsibilityHandoverJobId jobId,
            ResponsibilityAssignment source,
            PrincipalId createdByPrincipalId,
            UtcTimestamp occurredAt) {
        ResponsibilityAssignment requiredSource =
                Objects.requireNonNull(source, "source");
        return new ResponsibilityHandoverItem(
                id,
                requiredSource.scope().organizationId(),
                jobId,
                requiredSource.id(),
                requiredSource.workItemId(),
                requiredSource.scope(),
                requiredSource.version(),
                HandoverItemState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(createdByPrincipalId, occurredAt));
    }

    /** Reconstitutes a committed item without replaying lifecycle behavior. */
    public static ResponsibilityHandoverItem reconstitute(
            ResponsibilityHandoverItemId id,
            OrganizationId organizationId,
            ResponsibilityHandoverJobId jobId,
            ResponsibilityAssignmentId assignmentId,
            WorkItemId workItemId,
            WorkItemScope scope,
            long expectedAssignmentVersion,
            HandoverItemState state,
            Optional<ResponsibilityAssignmentId> resultAssignmentId,
            Optional<String> errorCode,
            Optional<UtcTimestamp> processedAt,
            long version,
            AuditMetadata audit) {
        return new ResponsibilityHandoverItem(
                id,
                organizationId,
                jobId,
                assignmentId,
                workItemId,
                scope,
                expectedAssignmentVersion,
                state,
                resultAssignmentId,
                errorCode,
                processedAt,
                version,
                audit);
    }

    /** Records a committed transfer and the assignment fact created for the target. */
    public ResponsibilityHandoverItem markDone(
            PrincipalId actor, ResponsibilityAssignmentId result, UtcTimestamp occurredAt) {
        return transitioned(
                HandoverItemState.DONE,
                Optional.of(Objects.requireNonNull(result, "result")),
                Optional.empty(),
                actor,
                occurredAt);
    }

    /** Records a stale-view stop: the source slot changed after the job was created. */
    public ResponsibilityHandoverItem markConflict(
            PrincipalId actor, String code, UtcTimestamp occurredAt) {
        return transitioned(
                HandoverItemState.CONFLICT,
                Optional.empty(),
                Optional.of(requireCode(code)),
                actor,
                occurredAt);
    }

    /** Records a policy stop: the actor or the target stopped being eligible to transfer. */
    public ResponsibilityHandoverItem markDenied(
            PrincipalId actor, String code, UtcTimestamp occurredAt) {
        return transitioned(
                HandoverItemState.DENIED,
                Optional.empty(),
                Optional.of(requireCode(code)),
                actor,
                occurredAt);
    }

    public boolean isPending() {
        return state == HandoverItemState.PENDING;
    }

    public ResponsibilityHandoverItemId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public ResponsibilityHandoverJobId jobId() {
        return jobId;
    }

    public ResponsibilityAssignmentId assignmentId() {
        return assignmentId;
    }

    public WorkItemId workItemId() {
        return workItemId;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public long expectedAssignmentVersion() {
        return expectedAssignmentVersion;
    }

    public HandoverItemState state() {
        return state;
    }

    public Optional<ResponsibilityAssignmentId> resultAssignmentId() {
        return resultAssignmentId;
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

    private ResponsibilityHandoverItem transitioned(
            HandoverItemState target,
            Optional<ResponsibilityAssignmentId> result,
            Optional<String> code,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        if (state != HandoverItemState.PENDING) {
            throw new InvalidStateTransitionException(
                    "ResponsibilityHandoverItem", id, state, target);
        }
        return new ResponsibilityHandoverItem(
                id,
                organizationId,
                jobId,
                assignmentId,
                workItemId,
                scope,
                expectedAssignmentVersion,
                target,
                result,
                code,
                Optional.of(occurredAt),
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static String requireCode(String code) {
        String required = Objects.requireNonNull(code, "code");
        if (required.isBlank()) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.errorCode", "must not be blank");
        }
        return required;
    }

    private static long requireExpectedVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.expectedAssignmentVersion",
                    "must not be negative");
        }
        return value;
    }

    private static Optional<ResponsibilityAssignmentId> requireResult(
            HandoverItemState state, Optional<ResponsibilityAssignmentId> resultAssignmentId) {
        Optional<ResponsibilityAssignmentId> required =
                Objects.requireNonNull(resultAssignmentId, "resultAssignmentId");
        // Only DONE names the target assignment the replay created; stopped items never do.
        boolean done = state == HandoverItemState.DONE;
        if (done != required.isPresent()) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.resultAssignmentId",
                    done ? "is required for a done item" : "is only allowed for a done item");
        }
        return required;
    }

    private static Optional<String> requireErrorCode(
            HandoverItemState state, Optional<String> errorCode) {
        Optional<String> required = Objects.requireNonNull(errorCode, "errorCode");
        // CONFLICT and DENIED must explain themselves; DONE and PENDING carry no code.
        boolean stopped = state == HandoverItemState.CONFLICT || state == HandoverItemState.DENIED;
        if (stopped == required.isEmpty()) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.errorCode",
                    stopped ? "is required for a stopped item" : "is only allowed for a stopped item");
        }
        return required;
    }

    private static Optional<UtcTimestamp> requireProcessedAt(
            HandoverItemState state, Optional<UtcTimestamp> processedAt) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(processedAt, "processedAt");
        if ((state != HandoverItemState.PENDING) != required.isPresent()) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.processedAt",
                    state != HandoverItemState.PENDING
                            ? "is required for a processed item"
                            : "is only allowed for a processed item");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "responsibilityHandoverItem.version", "must not be negative");
        }
        return value;
    }
}
