package io.crewscope.domain.responsibility;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.Objects;
import java.util.Optional;

/** Auditable responsibility fact connecting one eligible Principal to one WorkItem role. */
public final class ResponsibilityAssignment {

    private final ResponsibilityAssignmentId id;
    private final WorkItemScope scope;
    private final WorkItemId workItemId;
    private final ResponsibilityRole role;
    private final PrincipalId actorPrincipalId;
    private final PrincipalType actorType;
    private final Optional<TeamMemberId> actorMemberId;
    private final ResponsibilityAssignmentStatus status;
    private final PrincipalId assignedByPrincipalId;
    private final UtcTimestamp assignedAt;
    private final UtcTimestamp acceptedAt;
    private final Optional<PrincipalId> releasedByPrincipalId;
    private final Optional<UtcTimestamp> releasedAt;
    private final long version;
    private final AuditMetadata audit;

    private ResponsibilityAssignment(
            ResponsibilityAssignmentId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            ResponsibilityRole role,
            PrincipalId actorPrincipalId,
            PrincipalType actorType,
            Optional<TeamMemberId> actorMemberId,
            ResponsibilityAssignmentStatus status,
            PrincipalId assignedByPrincipalId,
            UtcTimestamp assignedAt,
            UtcTimestamp acceptedAt,
            Optional<PrincipalId> releasedByPrincipalId,
            Optional<UtcTimestamp> releasedAt,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId");
        this.role = Objects.requireNonNull(role, "role");
        this.actorPrincipalId = Objects.requireNonNull(actorPrincipalId, "actorPrincipalId");
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorMemberId = requireActorMember(actorType, actorMemberId);
        this.status = Objects.requireNonNull(status, "status");
        this.assignedByPrincipalId =
                Objects.requireNonNull(assignedByPrincipalId, "assignedByPrincipalId");
        this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt");
        this.acceptedAt = requireAcceptedAt(assignedAt, acceptedAt);
        this.releasedByPrincipalId = requireReleaseActor(status, releasedByPrincipalId);
        this.releasedAt = requireReleasedAt(status, acceptedAt, releasedAt);
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        requireRoleType(role, actorType);
    }

    /** Creates an immediately effective M1 assignment after validating subject and actor scope. */
    public static ResponsibilityAssignment assign(
            ResponsibilityAssignmentId id,
            WorkItem workItem,
            ResponsibilityRole role,
            Principal actor,
            Optional<TeamMember> actorMember,
            Principal assignedBy,
            UtcTimestamp occurredAt) {
        WorkItem requiredWorkItem = requireAssignable(workItem);
        Principal requiredActor = requirePrincipalInScope(
                actor, requiredWorkItem.scope(), "responsibilityAssignment.actorPrincipalId");
        Principal requiredAssigner = requirePrincipalInScope(
                assignedBy,
                requiredWorkItem.scope(),
                "responsibilityAssignment.assignedByPrincipalId");
        TeamMemberId memberId = requireActorQualification(
                requiredActor,
                actorMember,
                requiredWorkItem.scope(),
                Objects.requireNonNull(role, "role"));
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new ResponsibilityAssignment(
                id,
                requiredWorkItem.scope(),
                requiredWorkItem.id(),
                role,
                requiredActor.id(),
                requiredActor.type(),
                Optional.ofNullable(memberId),
                ResponsibilityAssignmentStatus.ACTIVE,
                requiredAssigner.id(),
                requiredTime,
                requiredTime,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(requiredAssigner.id(), requiredTime));
    }

    /** Reconstitutes a committed responsibility fact without replaying lifecycle behavior. */
    public static ResponsibilityAssignment reconstitute(
            ResponsibilityAssignmentId id,
            WorkItemScope scope,
            WorkItemId workItemId,
            ResponsibilityRole role,
            PrincipalId actorPrincipalId,
            PrincipalType actorType,
            Optional<TeamMemberId> actorMemberId,
            ResponsibilityAssignmentStatus status,
            PrincipalId assignedByPrincipalId,
            UtcTimestamp assignedAt,
            UtcTimestamp acceptedAt,
            Optional<PrincipalId> releasedByPrincipalId,
            Optional<UtcTimestamp> releasedAt,
            long version,
            AuditMetadata audit) {
        return new ResponsibilityAssignment(
                id,
                scope,
                workItemId,
                role,
                actorPrincipalId,
                actorType,
                actorMemberId,
                status,
                assignedByPrincipalId,
                assignedAt,
                acceptedAt,
                releasedByPrincipalId,
                releasedAt,
                version,
                audit);
    }

    /** Releases one active fact permanently and records the trusted initiating Principal. */
    public ResponsibilityAssignment release(Principal releasedBy, UtcTimestamp occurredAt) {
        if (status != ResponsibilityAssignmentStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "ResponsibilityAssignment",
                    id,
                    status,
                    ResponsibilityAssignmentStatus.RELEASED);
        }
        Principal requiredReleaser = requirePrincipalInScope(
                releasedBy, scope, "responsibilityAssignment.releasedByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(acceptedAt) < 0) {
            throw new DomainValidationException(
                    "responsibilityAssignment.releasedAt", "must not be before acceptedAt");
        }
        return new ResponsibilityAssignment(
                id,
                scope,
                workItemId,
                role,
                actorPrincipalId,
                actorType,
                actorMemberId,
                ResponsibilityAssignmentStatus.RELEASED,
                assignedByPrincipalId,
                assignedAt,
                acceptedAt,
                Optional.of(requiredReleaser.id()),
                Optional.of(requiredTime),
                version + 1,
                audit.modifiedBy(requiredReleaser.id(), requiredTime));
    }

    public boolean isActive() {
        return status == ResponsibilityAssignmentStatus.ACTIVE;
    }

    public ResponsibilityAssignmentId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public WorkItemId workItemId() {
        return workItemId;
    }

    public ResponsibilityRole role() {
        return role;
    }

    public PrincipalId actorPrincipalId() {
        return actorPrincipalId;
    }

    public PrincipalType actorType() {
        return actorType;
    }

    public Optional<TeamMemberId> actorMemberId() {
        return actorMemberId;
    }

    public ResponsibilityAssignmentStatus status() {
        return status;
    }

    public PrincipalId assignedByPrincipalId() {
        return assignedByPrincipalId;
    }

    public UtcTimestamp assignedAt() {
        return assignedAt;
    }

    public UtcTimestamp acceptedAt() {
        return acceptedAt;
    }

    public Optional<PrincipalId> releasedByPrincipalId() {
        return releasedByPrincipalId;
    }

    public Optional<UtcTimestamp> releasedAt() {
        return releasedAt;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private static WorkItem requireAssignable(WorkItem workItem) {
        WorkItem required = Objects.requireNonNull(workItem, "workItem");
        if (required.status() == WorkItemStatus.ARCHIVED) {
            throw new DomainValidationException(
                    "responsibilityAssignment.workItemId",
                    "must reference a non-archived WorkItem");
        }
        return required;
    }

    private static Principal requirePrincipalInScope(
            Principal principal, WorkItemScope scope, String field) {
        Principal required = Objects.requireNonNull(principal, "principal");
        if (!required.canAct()) {
            throw new DomainValidationException(field, "must reference an active Principal");
        }
        boolean hasDifferentTeam = required.scope().teamId().isPresent()
                && required.scope().teamId().filter(scope.teamId()::equals).isEmpty();
        if (!required.scope().organizationId().equals(scope.organizationId())
                || hasDifferentTeam) {
            throw new DomainValidationException(field, "must belong to the WorkItem scope");
        }
        return required;
    }

    private static TeamMemberId requireActorQualification(
            Principal actor,
            Optional<TeamMember> actorMember,
            WorkItemScope scope,
            ResponsibilityRole role) {
        Optional<TeamMember> requiredMember = Objects.requireNonNull(actorMember, "actorMember");
        requireRoleType(role, actor.type());
        if (actor.type() == PrincipalType.USER) {
            TeamMember member = requiredMember.orElseThrow(() -> new DomainValidationException(
                    "responsibilityAssignment.actorMemberId",
                    "is required for a USER responsibility actor"));
            if (!member.canParticipate()
                    || !member.userPrincipalId().equals(actor.id())
                    || !member.scope().organizationId().equals(scope.organizationId())
                    || !member.scope().teamId().equals(scope.teamId())) {
                throw new DomainValidationException(
                        "responsibilityAssignment.actorMemberId",
                        "must reference the actor's active membership in the WorkItem Team");
            }
            return member.id();
        }
        if (requiredMember.isPresent()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.actorMemberId",
                    "is only allowed for a USER responsibility actor");
        }
        if (actor.scope().teamId().filter(scope.teamId()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.actorPrincipalId",
                    "an Agent responsibility actor must belong to the WorkItem Team");
        }
        return null;
    }

    private static void requireRoleType(ResponsibilityRole role, PrincipalType actorType) {
        boolean allowed = switch (Objects.requireNonNull(role, "role")) {
            case OWNER -> actorType == PrincipalType.USER;
            case EXECUTOR -> actorType == PrincipalType.USER || actorType.isAgent();
            case REVIEWER ->
                actorType == PrincipalType.USER || actorType == PrincipalType.SPECIALIST_AGENT;
        };
        if (!allowed) {
            throw new DomainValidationException(
                    "responsibilityAssignment.actorPrincipalId",
                    "Principal type " + actorType + " cannot hold role " + role);
        }
    }

    private static Optional<TeamMemberId> requireActorMember(
            PrincipalType actorType, Optional<TeamMemberId> actorMemberId) {
        Optional<TeamMemberId> required = Objects.requireNonNull(actorMemberId, "actorMemberId");
        if (actorType == PrincipalType.USER && required.isEmpty()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.actorMemberId",
                    "is required for a USER responsibility actor");
        }
        if (actorType != PrincipalType.USER && required.isPresent()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.actorMemberId",
                    "is only allowed for a USER responsibility actor");
        }
        return required;
    }

    private static UtcTimestamp requireAcceptedAt(
            UtcTimestamp assignedAt, UtcTimestamp acceptedAt) {
        UtcTimestamp required = Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (required.compareTo(assignedAt) < 0) {
            throw new DomainValidationException(
                    "responsibilityAssignment.acceptedAt", "must not be before assignedAt");
        }
        return required;
    }

    private static Optional<PrincipalId> requireReleaseActor(
            ResponsibilityAssignmentStatus status, Optional<PrincipalId> releasedBy) {
        Optional<PrincipalId> required = Objects.requireNonNull(releasedBy, "releasedByPrincipalId");
        if ((status == ResponsibilityAssignmentStatus.RELEASED) != required.isPresent()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.releasedByPrincipalId",
                    status == ResponsibilityAssignmentStatus.RELEASED
                            ? "is required for a released assignment"
                            : "is only allowed for a released assignment");
        }
        return required;
    }

    private static Optional<UtcTimestamp> requireReleasedAt(
            ResponsibilityAssignmentStatus status,
            UtcTimestamp acceptedAt,
            Optional<UtcTimestamp> releasedAt) {
        Optional<UtcTimestamp> required = Objects.requireNonNull(releasedAt, "releasedAt");
        if ((status == ResponsibilityAssignmentStatus.RELEASED) != required.isPresent()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.releasedAt",
                    status == ResponsibilityAssignmentStatus.RELEASED
                            ? "is required for a released assignment"
                            : "is only allowed for a released assignment");
        }
        if (required.filter(value -> value.compareTo(acceptedAt) < 0).isPresent()) {
            throw new DomainValidationException(
                    "responsibilityAssignment.releasedAt", "must not be before acceptedAt");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "responsibilityAssignment.version", "must not be negative");
        }
        return value;
    }
}
