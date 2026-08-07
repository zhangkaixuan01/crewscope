package io.crewscope.domain.workspace;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable product configuration identity for a Personal, Team or Specialist Agent. */
public final class AgentProfile {

    private static final Map<AgentProfileStatus, Set<AgentProfileStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    AgentProfileStatus.ACTIVE,
                    EnumSet.of(AgentProfileStatus.DISABLED, AgentProfileStatus.ARCHIVED),
                    AgentProfileStatus.DISABLED,
                    EnumSet.of(AgentProfileStatus.ACTIVE, AgentProfileStatus.ARCHIVED),
                    AgentProfileStatus.ARCHIVED,
                    EnumSet.noneOf(AgentProfileStatus.class));

    private final AgentProfileId id;
    private final WorkspaceScope scope;
    private final WorkspaceId workspaceId;
    private final PrincipalId agentPrincipalId;
    private final Optional<TeamMemberId> ownerMemberId;
    private final AgentProfileType type;
    private final boolean defaultProfile;
    private final AgentProfileStatus status;
    private final long version;
    private final AuditMetadata audit;

    private AgentProfile(
            AgentProfileId id,
            WorkspaceScope scope,
            WorkspaceId workspaceId,
            PrincipalId agentPrincipalId,
            Optional<TeamMemberId> ownerMemberId,
            AgentProfileType type,
            boolean defaultProfile,
            AgentProfileStatus status,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        this.type = Objects.requireNonNull(type, "type");
        this.ownerMemberId = requireOwnerMember(this.type, ownerMemberId);
        this.defaultProfile = defaultProfile;
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** Creates the only active default Personal Agent profile for one active Team member. */
    public static AgentProfile createDefaultPersonal(
            AgentProfileId id,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            Principal personalAgent,
            UtcTimestamp occurredAt) {
        Workspace requiredWorkspace = requireActiveTeamWorkspace(workspace, ownerMember);
        PrincipalId ownerPrincipalId = requireOwner(ownerMember, ownerUser);
        requirePersonalAgent(ownerMember, ownerPrincipalId, personalAgent);
        return new AgentProfile(
                id,
                requiredWorkspace.scope(),
                requiredWorkspace.id(),
                personalAgent.id(),
                Optional.of(ownerMember.id()),
                AgentProfileType.PERSONAL,
                true,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(ownerPrincipalId, occurredAt));
    }

    /** Reconstitutes persisted profile state without replaying a lifecycle transition. */
    public static AgentProfile reconstitute(
            AgentProfileId id,
            WorkspaceScope scope,
            WorkspaceId workspaceId,
            PrincipalId agentPrincipalId,
            Optional<TeamMemberId> ownerMemberId,
            AgentProfileType type,
            boolean defaultProfile,
            AgentProfileStatus status,
            long version,
            AuditMetadata audit) {
        return new AgentProfile(
                id,
                scope,
                workspaceId,
                agentPrincipalId,
                ownerMemberId,
                type,
                defaultProfile,
                status,
                version,
                audit);
    }

    public AgentProfile activate(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(AgentProfileStatus.ACTIVE, actor, occurredAt);
    }

    public AgentProfile disable(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(AgentProfileStatus.DISABLED, actor, occurredAt);
    }

    public AgentProfile archive(PrincipalId actor, UtcTimestamp occurredAt) {
        return transitionTo(AgentProfileStatus.ARCHIVED, actor, occurredAt);
    }

    public boolean isActiveDefaultPersonal() {
        return type == AgentProfileType.PERSONAL
                && defaultProfile
                && status == AgentProfileStatus.ACTIVE;
    }

    public AgentProfileId id() {
        return id;
    }

    public WorkspaceScope scope() {
        return scope;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public PrincipalId agentPrincipalId() {
        return agentPrincipalId;
    }

    public Optional<TeamMemberId> ownerMemberId() {
        return ownerMemberId;
    }

    public AgentProfileType type() {
        return type;
    }

    public boolean defaultProfile() {
        return defaultProfile;
    }

    public AgentProfileStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private AgentProfile transitionTo(
            AgentProfileStatus target, PrincipalId actor, UtcTimestamp occurredAt) {
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("AgentProfile", id, status, target);
        }
        return new AgentProfile(
                id,
                scope,
                workspaceId,
                agentPrincipalId,
                ownerMemberId,
                type,
                defaultProfile,
                target,
                version + 1,
                audit.modifiedBy(actor, occurredAt));
    }

    private static Workspace requireActiveTeamWorkspace(
            Workspace workspace, TeamMember ownerMember) {
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        TeamMember requiredMember = Objects.requireNonNull(ownerMember, "ownerMember");
        if (requiredWorkspace.type() != WorkspaceType.TEAM
                || requiredWorkspace.status() != WorkspaceStatus.ACTIVE
                || !requiredWorkspace
                        .scope()
                        .organizationId()
                        .equals(requiredMember.scope().organizationId())
                || requiredWorkspace.scope().teamId()
                        .filter(requiredMember.scope().teamId()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "agentProfile.workspaceId",
                    "must reference the active Team Workspace of the owner member");
        }
        if (!requiredMember.canParticipate()) {
            throw new DomainValidationException(
                    "agentProfile.ownerMemberId", "must reference an active Team member");
        }
        return requiredWorkspace;
    }

    private static PrincipalId requireOwner(TeamMember ownerMember, Principal ownerUser) {
        Principal requiredOwner = Objects.requireNonNull(ownerUser, "ownerUser");
        if (requiredOwner.type() != PrincipalType.USER
                || !requiredOwner.canAct()
                || !requiredOwner.id().equals(ownerMember.userPrincipalId())
                || !requiredOwner
                        .scope()
                        .organizationId()
                        .equals(ownerMember.scope().organizationId())) {
            throw new DomainValidationException(
                    "agentProfile.ownerMemberId",
                    "must reference the active membership of the owner USER Principal");
        }
        return requiredOwner.id();
    }

    private static void requirePersonalAgent(
            TeamMember ownerMember, PrincipalId ownerPrincipalId, Principal personalAgent) {
        Principal requiredAgent = Objects.requireNonNull(personalAgent, "personalAgent");
        if (requiredAgent.type() != PrincipalType.PERSONAL_AGENT
                || requiredAgent.status() != PrincipalStatus.ACTIVE
                || requiredAgent.visibility() != PrincipalVisibility.PRIVATE
                || requiredAgent.ownerPrincipalId().filter(ownerPrincipalId::equals).isEmpty()
                || !requiredAgent
                        .scope()
                        .organizationId()
                        .equals(ownerMember.scope().organizationId())
                || requiredAgent.scope().teamId()
                        .filter(ownerMember.scope().teamId()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "agentProfile.agentPrincipalId",
                    "must reference the active Personal Agent owned by this Team member");
        }
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("agentProfile.version", "must not be negative");
        }
        return value;
    }

    private static Optional<TeamMemberId> requireOwnerMember(
            AgentProfileType type, Optional<TeamMemberId> ownerMemberId) {
        Optional<TeamMemberId> requiredOwner =
                Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        if (type == AgentProfileType.PERSONAL && requiredOwner.isEmpty()) {
            throw new DomainValidationException(
                    "agentProfile.ownerMemberId",
                    "is required for a Personal Agent profile");
        }
        return requiredOwner;
    }
}
