package io.crewscope.domain.inbox;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Generation-independent authority recording a member's monotonic Inbox disposition. */
public final class InboxDisposition {

    private final InboxItemId inboxItemId;
    private final OrganizationId organizationId;
    private final TeamId teamId;
    private final TeamMemberId memberId;
    private final InboxDispositionStatus status;
    private final long version;
    private final PrincipalId updatedByPrincipalId;
    private final UtcTimestamp updatedAt;

    private InboxDisposition(
            InboxItemId inboxItemId,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            InboxDispositionStatus status,
            long version,
            PrincipalId updatedByPrincipalId,
            UtcTimestamp updatedAt) {
        this.inboxItemId = Objects.requireNonNull(inboxItemId, "inboxItemId");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.status = requirePersistedStatus(status);
        if (version < 1) {
            throw new DomainValidationException(
                    "inboxDisposition.version", "persisted version must be positive");
        }
        this.version = version;
        this.updatedByPrincipalId =
                Objects.requireNonNull(updatedByPrincipalId, "updatedByPrincipalId");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** Creates the first authority row; absence before this command represents version 0 UNREAD. */
    public static InboxDisposition create(
            InboxItem item,
            InboxDispositionStatus target,
            long expectedVersion,
            PrincipalId actorPrincipalId,
            UtcTimestamp occurredAt) {
        InboxItem requiredItem = Objects.requireNonNull(item, "item");
        if (expectedVersion != 0) {
            throw new OptimisticLockConflictException(
                    "InboxDisposition", requiredItem.id(), expectedVersion, 0);
        }
        return new InboxDisposition(
                requiredItem.id(),
                requiredItem.organizationId(),
                requiredItem.teamId(),
                requiredItem.memberId(),
                target,
                1,
                actorPrincipalId,
                occurredAt);
    }

    /** Reconstitutes a committed member authority fact outside any projection generation. */
    public static InboxDisposition reconstitute(
            InboxItemId inboxItemId,
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            InboxDispositionStatus status,
            long version,
            PrincipalId updatedByPrincipalId,
            UtcTimestamp updatedAt) {
        return new InboxDisposition(
                inboxItemId,
                organizationId,
                teamId,
                memberId,
                status,
                version,
                updatedByPrincipalId,
                updatedAt);
    }

    /** Advances member disposition with an exact ETag and rejects reverse transitions. */
    public InboxDisposition transitionTo(
            InboxDispositionStatus target,
            long expectedVersion,
            PrincipalId actorPrincipalId,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        InboxDispositionStatus requiredTarget = requirePersistedStatus(target);
        if (requiredTarget == status) {
            return this;
        }
        if (!requiredTarget.isAfter(status)) {
            throw new InvalidStateTransitionException(
                    "InboxDisposition", inboxItemId, status, requiredTarget);
        }
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(updatedAt) < 0) {
            throw new DomainValidationException(
                    "inboxDisposition.updatedAt", "must not move backwards");
        }
        if (version == Long.MAX_VALUE) {
            throw new IllegalStateException("InboxDisposition version is exhausted");
        }
        return new InboxDisposition(
                inboxItemId,
                organizationId,
                teamId,
                memberId,
                requiredTarget,
                version + 1,
                Objects.requireNonNull(actorPrincipalId, "actorPrincipalId"),
                requiredTime);
    }

    public boolean belongsTo(InboxItem item) {
        InboxItem required = Objects.requireNonNull(item, "item");
        return inboxItemId.equals(required.id())
                && organizationId.equals(required.organizationId())
                && teamId.equals(required.teamId())
                && memberId.equals(required.memberId());
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "InboxDisposition", inboxItemId, expectedVersion, version);
        }
    }

    private static InboxDispositionStatus requirePersistedStatus(InboxDispositionStatus value) {
        InboxDispositionStatus required = Objects.requireNonNull(value, "status");
        if (required == InboxDispositionStatus.UNREAD) {
            throw new DomainValidationException(
                    "inboxDisposition.status", "UNREAD is represented by the absence of a row");
        }
        return required;
    }

    public InboxItemId inboxItemId() {
        return inboxItemId;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public TeamId teamId() {
        return teamId;
    }

    public TeamMemberId memberId() {
        return memberId;
    }

    public InboxDispositionStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public PrincipalId updatedByPrincipalId() {
        return updatedByPrincipalId;
    }

    public UtcTimestamp updatedAt() {
        return updatedAt;
    }
}
