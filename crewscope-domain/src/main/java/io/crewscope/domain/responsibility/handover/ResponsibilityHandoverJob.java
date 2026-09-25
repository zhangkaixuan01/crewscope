package io.crewscope.domain.responsibility.handover;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/**
 * One member's active responsibility facts of one role, queued for transfer to one target
 * Principal (ADR-038 §1). The job only records what to move and where; each item commits in its
 * own short transaction by replaying the ordinary responsibility commands, so an interrupted run
 * resumes at the first PENDING item and never replays a DONE one.
 */
public final class ResponsibilityHandoverJob {

    private final ResponsibilityHandoverJobId id;
    private final OrganizationId organizationId;
    private final TeamId teamId;
    private final TeamMemberId sourceMemberId;
    private final PrincipalId targetPrincipalId;
    private final ResponsibilityRole role;
    private final String commandId;
    private final PrincipalId createdByPrincipalId;
    private final long sourceAuthorizationVersion;
    private final HandoverJobStatus status;
    private final long version;
    private final AuditMetadata audit;

    private ResponsibilityHandoverJob(
            ResponsibilityHandoverJobId id,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId sourceMemberId,
            PrincipalId targetPrincipalId,
            ResponsibilityRole role,
            String commandId,
            PrincipalId createdByPrincipalId,
            long sourceAuthorizationVersion,
            HandoverJobStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.sourceMemberId = Objects.requireNonNull(sourceMemberId, "sourceMemberId");
        this.targetPrincipalId = Objects.requireNonNull(targetPrincipalId, "targetPrincipalId");
        this.role = Objects.requireNonNull(role, "role");
        this.commandId = requireCommandId(commandId);
        this.createdByPrincipalId =
                Objects.requireNonNull(createdByPrincipalId, "createdByPrincipalId");
        if (sourceAuthorizationVersion < 1) {
            throw new DomainValidationException(
                    "responsibilityHandoverJob.sourceAuthorizationVersion", "must be positive");
        }
        this.sourceAuthorizationVersion = sourceAuthorizationVersion;
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Queues a new job pinned to the source member's authorization snapshot at creation time. */
    public static ResponsibilityHandoverJob create(
            ResponsibilityHandoverJobId id,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId sourceMemberId,
            PrincipalId targetPrincipalId,
            ResponsibilityRole role,
            String commandId,
            PrincipalId createdByPrincipalId,
            long sourceAuthorizationVersion,
            UtcTimestamp occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return new ResponsibilityHandoverJob(
                id,
                organizationId,
                teamId,
                sourceMemberId,
                targetPrincipalId,
                role,
                commandId,
                createdByPrincipalId,
                sourceAuthorizationVersion,
                HandoverJobStatus.PENDING,
                0,
                AuditMetadata.createdBy(createdByPrincipalId, occurredAt));
    }

    /** Reconstitutes a committed job without replaying lifecycle behavior. */
    public static ResponsibilityHandoverJob reconstitute(
            ResponsibilityHandoverJobId id,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId sourceMemberId,
            PrincipalId targetPrincipalId,
            ResponsibilityRole role,
            String commandId,
            PrincipalId createdByPrincipalId,
            long sourceAuthorizationVersion,
            HandoverJobStatus status,
            long version,
            AuditMetadata audit) {
        return new ResponsibilityHandoverJob(
                id,
                organizationId,
                teamId,
                sourceMemberId,
                targetPrincipalId,
                role,
                commandId,
                createdByPrincipalId,
                sourceAuthorizationVersion,
                status,
                version,
                audit);
    }

    /** Claims a PENDING job for processing; an interrupted RUNNING job may also be reclaimed. */
    public ResponsibilityHandoverJob markRunning(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != HandoverJobStatus.PENDING && status != HandoverJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "ResponsibilityHandoverJob", id, status, HandoverJobStatus.RUNNING);
        }
        return transitioned(HandoverJobStatus.RUNNING, actor, occurredAt);
    }

    /** Seals the job after every item reached a terminal state. */
    public ResponsibilityHandoverJob markCompleted(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != HandoverJobStatus.PENDING && status != HandoverJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "ResponsibilityHandoverJob", id, status, HandoverJobStatus.COMPLETED);
        }
        return transitioned(HandoverJobStatus.COMPLETED, actor, occurredAt);
    }

    /** Stops a job that still has unprocessed items; already DONE items are never rolled back. */
    public ResponsibilityHandoverJob markCancelled(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status != HandoverJobStatus.PENDING && status != HandoverJobStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "ResponsibilityHandoverJob", id, status, HandoverJobStatus.CANCELLED);
        }
        return transitioned(HandoverJobStatus.CANCELLED, actor, occurredAt);
    }

    public boolean isTerminal() {
        return status == HandoverJobStatus.COMPLETED || status == HandoverJobStatus.CANCELLED;
    }

    public ResponsibilityHandoverJobId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public TeamId teamId() {
        return teamId;
    }

    public TeamMemberId sourceMemberId() {
        return sourceMemberId;
    }

    public PrincipalId targetPrincipalId() {
        return targetPrincipalId;
    }

    public ResponsibilityRole role() {
        return role;
    }

    public String commandId() {
        return commandId;
    }

    public PrincipalId createdByPrincipalId() {
        return createdByPrincipalId;
    }

    public long sourceAuthorizationVersion() {
        return sourceAuthorizationVersion;
    }

    public HandoverJobStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private ResponsibilityHandoverJob transitioned(
            HandoverJobStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        return new ResponsibilityHandoverJob(
                id,
                organizationId,
                teamId,
                sourceMemberId,
                targetPrincipalId,
                role,
                commandId,
                createdByPrincipalId,
                sourceAuthorizationVersion,
                target,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static String requireCommandId(String commandId) {
        String required = Objects.requireNonNull(commandId, "commandId");
        if (required.isBlank()) {
            throw new DomainValidationException(
                    "responsibilityHandoverJob.commandId", "must not be blank");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "responsibilityHandoverJob.version", "must not be negative");
        }
        return value;
    }
}
