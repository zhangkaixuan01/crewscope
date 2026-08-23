package io.crewscope.domain.workspace;

import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable template-backed product identity for a Personal, Team or Specialist Agent. */
public final class AgentProfile {

    private static final AgentTemplateVersion LEGACY_PERSONAL_TEMPLATE =
            AgentTemplateVersion.of("personal-assistant", 1);
    private static final AgentTemplateVersion LEGACY_TEAM_TEMPLATE =
            AgentTemplateVersion.of("team-coordinator", 1);
    private static final AgentTemplateVersion LEGACY_SPECIALIST_TEMPLATE =
            AgentTemplateVersion.of("coding", 1);

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
    private final AgentOwnership ownership;
    private final AgentRuntimeRole runtimeRole;
    private final AgentTemplateVersion templateVersion;
    private final AgentProfileType type;
    private final boolean defaultProfile;
    private final AgentProfileStatus status;
    private final long version;
    private final AuditMetadata audit;
    private final boolean currentShape;

    private AgentProfile(
            AgentProfileId id,
            WorkspaceScope scope,
            WorkspaceId workspaceId,
            PrincipalId agentPrincipalId,
            AgentOwnership ownership,
            AgentRuntimeRole runtimeRole,
            AgentTemplateVersion templateVersion,
            AgentProfileType type,
            boolean defaultProfile,
            AgentProfileStatus status,
            long version,
            AuditMetadata audit,
            boolean requireCurrentShape) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        this.ownership = requireOwnership(this.scope, ownership);
        this.runtimeRole = Objects.requireNonNull(runtimeRole, "runtimeRole");
        this.templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultProfile = defaultProfile;
        this.status = Objects.requireNonNull(status, "status");
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentShape = requireCurrentShape;
        if (requireCurrentShape) {
            requireCurrentShape();
        } else {
            requireLegacyShape();
        }
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
                AgentOwnership.user(
                        requiredWorkspace.scope().organizationId(),
                        requiredWorkspace.scope().teamId().orElseThrow(),
                        ownerMember.id()),
                AgentRuntimeRole.PERSONAL_ASSISTANT,
                LEGACY_PERSONAL_TEMPLATE,
                AgentProfileType.PERSONAL,
                true,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(ownerPrincipalId, occurredAt),
                true);
    }

    /** Creates a new Agent instance from one exact active template version. */
    public static AgentProfile createTemplateInstance(
            AgentProfileId id,
            Workspace workspace,
            Principal agentPrincipal,
            AgentOwnership ownership,
            AgentTemplateDefinition template,
            boolean defaultProfile,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (requiredWorkspace.status() != WorkspaceStatus.ACTIVE) {
            throw new DomainValidationException(
                    "agentProfile.workspaceId", "must reference an active Workspace");
        }
        Principal requiredPrincipal = Objects.requireNonNull(agentPrincipal, "agentPrincipal");
        if (!requiredPrincipal.canAct()) {
            throw new DomainValidationException(
                    "agentProfile.agentPrincipalId", "must reference an active Agent Principal");
        }
        AgentOwnership requiredOwnership = Objects.requireNonNull(ownership, "ownership");
        AgentTemplateDefinition requiredTemplate = Objects.requireNonNull(template, "template");
        if (requiredTemplate.runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT) {
            throw new DomainValidationException(
                    "agentProfile.runtimeRole",
                    "a default Personal Assistant must use the atomic default initializer");
        }
        requiredTemplate.requireInstantiable(requiredOwnership);
        requirePrincipalCompatibility(
                requiredWorkspace.scope(),
                requiredPrincipal,
                requiredOwnership,
                requiredTemplate.runtimeRole());
        return new AgentProfile(
                id,
                requiredWorkspace.scope(),
                requiredWorkspace.id(),
                requiredPrincipal.id(),
                requiredOwnership,
                requiredTemplate.runtimeRole(),
                requiredTemplate.templateVersion(),
                profileTypeFor(requiredTemplate.runtimeRole()),
                defaultProfile,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, occurredAt),
                true);
    }

    /**
     * Reconstitutes M2-M4 state and applies the deterministic V20 compatibility projection. No
     * display name, Prompt or historical output participates in this mapping.
     */
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
                legacyOwnership(scope, type, ownerMemberId),
                legacyRuntimeRole(type),
                legacyTemplateVersion(type),
                type,
                defaultProfile,
                status,
                version,
                audit,
                false);
    }

    /** Reconstitutes an M5 template-backed Agent and validates all explicit coordinates. */
    public static AgentProfile reconstituteTemplateInstance(
            AgentProfileId id,
            WorkspaceScope scope,
            WorkspaceId workspaceId,
            PrincipalId agentPrincipalId,
            AgentOwnership ownership,
            AgentRuntimeRole runtimeRole,
            AgentTemplateVersion templateVersion,
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
                ownership,
                runtimeRole,
                templateVersion,
                type,
                defaultProfile,
                status,
                version,
                audit,
                true);
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
        return runtimeRole == AgentRuntimeRole.PERSONAL_ASSISTANT
                && ownership.type() == AgentOwnershipType.USER
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
        return ownership.ownerMemberId();
    }

    public AgentOwnership ownership() {
        return ownership;
    }

    public AgentRuntimeRole runtimeRole() {
        return runtimeRole;
    }

    public AgentTemplateVersion templateVersion() {
        return templateVersion;
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
                ownership,
                runtimeRole,
                templateVersion,
                type,
                defaultProfile,
                target,
                version + 1,
                audit.modifiedBy(actor, occurredAt),
                currentShape);
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

    private void requireLegacyShape() {
        if (type == AgentProfileType.PERSONAL && ownership.ownerMemberId().isEmpty()) {
            throw new DomainValidationException(
                    "agentProfile.ownerMemberId",
                    "is required for a Personal Agent profile");
        }
    }

    private void requireCurrentShape() {
        if (type != profileTypeFor(runtimeRole)) {
            throw new DomainValidationException(
                    "agentProfile.type", "must match the Agent template runtime role");
        }
        if (runtimeRole == AgentRuntimeRole.PERSONAL_ASSISTANT) {
            if (ownership.type() != AgentOwnershipType.USER || !defaultProfile) {
                throw new DomainValidationException(
                        "agentProfile.defaultProfile",
                        "a Personal Assistant must be the USER owner's default profile");
            }
        } else if (defaultProfile) {
            throw new DomainValidationException(
                    "agentProfile.defaultProfile",
                    "only a Personal Assistant can be a default profile");
        }
    }

    private static AgentOwnership requireOwnership(
            WorkspaceScope scope, AgentOwnership ownership) {
        AgentOwnership requiredOwnership = Objects.requireNonNull(ownership, "ownership");
        if (!scope.organizationId().equals(requiredOwnership.organizationId())) {
            throw new DomainValidationException(
                    "agentProfile.ownership", "must belong to the Profile Organization");
        }
        requiredOwnership.teamId().ifPresent(ownerTeamId -> {
            if (scope.teamId().filter(ownerTeamId::equals).isEmpty()) {
                throw new DomainValidationException(
                        "agentProfile.ownership", "must belong to the Profile Team");
            }
        });
        return requiredOwnership;
    }

    private static AgentOwnership legacyOwnership(
            WorkspaceScope scope,
            AgentProfileType type,
            Optional<TeamMemberId> ownerMemberId) {
        WorkspaceScope requiredScope = Objects.requireNonNull(scope, "scope");
        AgentProfileType requiredType = Objects.requireNonNull(type, "type");
        Optional<TeamMemberId> requiredOwner = Objects.requireNonNull(
                ownerMemberId, "ownerMemberId");
        return switch (requiredType) {
            case PERSONAL -> AgentOwnership.user(
                    requiredScope.organizationId(),
                    requiredTeamId(requiredScope),
                    requiredOwner.orElseThrow(() -> new DomainValidationException(
                            "agentProfile.ownerMemberId",
                            "is required for a Personal Agent profile")));
            case TEAM -> AgentOwnership.team(
                    requiredScope.organizationId(), requiredTeamId(requiredScope));
            case SPECIALIST -> requiredOwner
                    .<AgentOwnership>map(owner -> AgentOwnership.user(
                            requiredScope.organizationId(), requiredTeamId(requiredScope), owner))
                    .orElseGet(() -> AgentOwnership.team(
                            requiredScope.organizationId(), requiredTeamId(requiredScope)));
        };
    }

    private static TeamId requiredTeamId(WorkspaceScope scope) {
        return scope.teamId().orElseThrow(() -> new DomainValidationException(
                "agentProfile.scope", "a legacy Agent Profile requires a Team"));
    }

    private static AgentRuntimeRole legacyRuntimeRole(AgentProfileType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case PERSONAL -> AgentRuntimeRole.PERSONAL_ASSISTANT;
            case TEAM -> AgentRuntimeRole.TEAM_COORDINATOR;
            case SPECIALIST -> AgentRuntimeRole.SPECIALIST;
        };
    }

    private static AgentTemplateVersion legacyTemplateVersion(AgentProfileType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case PERSONAL -> LEGACY_PERSONAL_TEMPLATE;
            case TEAM -> LEGACY_TEAM_TEMPLATE;
            case SPECIALIST -> LEGACY_SPECIALIST_TEMPLATE;
        };
    }

    private static AgentProfileType profileTypeFor(AgentRuntimeRole runtimeRole) {
        return switch (Objects.requireNonNull(runtimeRole, "runtimeRole")) {
            case PERSONAL_ASSISTANT -> AgentProfileType.PERSONAL;
            case TEAM_COORDINATOR -> AgentProfileType.TEAM;
            case SPECIALIST -> AgentProfileType.SPECIALIST;
        };
    }

    private static void requirePrincipalCompatibility(
            WorkspaceScope workspaceScope,
            Principal principal,
            AgentOwnership ownership,
            AgentRuntimeRole runtimeRole) {
        PrincipalType expectedType = switch (runtimeRole) {
            case PERSONAL_ASSISTANT -> PrincipalType.PERSONAL_AGENT;
            case TEAM_COORDINATOR -> PrincipalType.TEAM_AGENT;
            case SPECIALIST -> PrincipalType.SPECIALIST_AGENT;
        };
        if (principal.type() != expectedType
                || !principal.scope().organizationId().equals(workspaceScope.organizationId())
                || !principal.scope().teamId().equals(workspaceScope.teamId())) {
            throw new DomainValidationException(
                    "agentProfile.agentPrincipalId",
                    "must match the template runtime role and Workspace scope");
        }
        PrincipalVisibility expectedVisibility = switch (ownership.type()) {
            case USER -> PrincipalVisibility.PRIVATE;
            case TEAM -> PrincipalVisibility.TEAM;
            case ORGANIZATION -> PrincipalVisibility.ORGANIZATION;
        };
        if (principal.visibility() != expectedVisibility) {
            throw new DomainValidationException(
                    "agentProfile.agentPrincipalId",
                    "visibility must match the explicit Agent ownership");
        }
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException("agentProfile.version", "must not be negative");
        }
        return value;
    }
}
