package io.crewscope.domain.team;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Durable collaboration boundary with one accountable owner and one default Team Workspace. */
public final class Team {

    public static final int MAX_NAME_LENGTH = 200;

    private final TeamId id;
    private final OrganizationId organizationId;
    private final String name;
    private final TeamMemberId ownerMemberId;
    private final WorkspaceId defaultWorkspaceId;
    private final TeamStatus status;
    private final long version;
    private final AuditMetadata audit;

    private Team(
            TeamId id,
            OrganizationId organizationId,
            String name,
            TeamMemberId ownerMemberId,
            WorkspaceId defaultWorkspaceId,
            TeamStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.name = requireName(name);
        this.ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        this.defaultWorkspaceId = Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates an active Team whose cross-references are persisted in the initialization transaction. */
    public static Team create(
            TeamId id,
            OrganizationId organizationId,
            String name,
            TeamMemberId ownerMemberId,
            WorkspaceId defaultWorkspaceId,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return new Team(
                id,
                organizationId,
                name,
                ownerMemberId,
                defaultWorkspaceId,
                TeamStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt));
    }

    /** Reconstitutes a committed Team without applying a state transition. */
    public static Team reconstitute(
            TeamId id,
            OrganizationId organizationId,
            String name,
            TeamMemberId ownerMemberId,
            WorkspaceId defaultWorkspaceId,
            TeamStatus status,
            long version,
            AuditMetadata audit) {
        return new Team(
                id,
                organizationId,
                name,
                ownerMemberId,
                defaultWorkspaceId,
                status,
                version,
                audit);
    }

    /** Creates an active membership only while this Team accepts collaborative work. */
    public TeamMember joinMember(
            TeamMemberId memberId,
            Principal userPrincipal,
            TeamJoinMethod joinMethod,
            UtcTimestamp occurredAt) {
        ensureActive("join a member");
        return TeamMember.join(
                memberId, scope(), userPrincipal, joinMethod, occurredAt);
    }

    /** Creates a pending invitation only while this Team accepts collaborative work. */
    public TeamMember inviteMember(
            TeamMemberId memberId,
            Principal userPrincipal,
            PrincipalId invitedBy,
            UtcTimestamp occurredAt) {
        ensureActive("invite a member");
        return TeamMember.invite(memberId, scope(), userPrincipal, invitedBy, occurredAt);
    }

    /** Changes the accountable owner reference; role grants are changed in the same application transaction. */
    public Team transferOwnership(
            TeamMember newOwner, PrincipalId actor, UtcTimestamp occurredAt) {
        ensureActive("transfer ownership");
        TeamMember requiredOwner = Objects.requireNonNull(newOwner, "newOwner");
        if (!scope().equals(requiredOwner.scope()) || !requiredOwner.canParticipate()) {
            throw new DomainValidationException(
                    "team.ownerMemberId", "must reference an active member of this Team");
        }
        if (ownerMemberId.equals(requiredOwner.id())) {
            throw new DomainValidationException(
                    "team.ownerMemberId", "already references this TeamMember");
        }
        return new Team(
                id,
                organizationId,
                name,
                requiredOwner.id(),
                defaultWorkspaceId,
                status,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    public Team archive(PrincipalId actor, UtcTimestamp occurredAt) {
        if (status == TeamStatus.ARCHIVED) {
            throw new InvalidStateTransitionException(
                    "Team", id, TeamStatus.ARCHIVED, TeamStatus.ARCHIVED);
        }
        return new Team(
                id,
                organizationId,
                name,
                ownerMemberId,
                defaultWorkspaceId,
                TeamStatus.ARCHIVED,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    public boolean isActive() {
        return status == TeamStatus.ACTIVE;
    }

    public boolean isOwner(TeamMemberId memberId) {
        return ownerMemberId.equals(Objects.requireNonNull(memberId, "memberId"));
    }

    public TeamScope scope() {
        return new TeamScope(organizationId, id);
    }

    public TeamId id() {
        return id;
    }

    public OrganizationId organizationId() {
        return organizationId;
    }

    public String name() {
        return name;
    }

    public TeamMemberId ownerMemberId() {
        return ownerMemberId;
    }

    public WorkspaceId defaultWorkspaceId() {
        return defaultWorkspaceId;
    }

    public TeamStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private void ensureActive(String operation) {
        if (!isActive()) {
            throw new DomainValidationException(
                    "team.status", "must be ACTIVE to " + operation);
        }
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("team.name", "must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException(
                    "team.name", "must contain at most " + MAX_NAME_LENGTH + " characters");
        }
        return normalized;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("team.version", "must not be negative");
        }
        return value;
    }
}
