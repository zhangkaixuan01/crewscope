package io.crewscope.domain.distiller;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.WorkspaceStatus;
import io.crewscope.domain.workspace.WorkspaceType;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stable Team Agent Principal/Profile pair logic shared by the built-in Knowledge and
 * Skill Distillers (M13-C03): provisioned lazily as a DISABLED pair bound to the Team
 * default Workspace, activated only once an exact current TEAM configuration exists so
 * the full governance chain (data policy, price gate, credential governance) applies
 * before any model call. Per-kind inputs are the deterministic id namespaces, the
 * display name and the template contract.
 */
public final class DistillerInitializationContract {

    private final DistillerTemplateContract template;
    private final String principalIdNamespace;
    private final String profileIdNamespace;
    private final String displayName;
    private final String errorPrefix;

    private DistillerInitializationContract(
            DistillerTemplateContract template,
            String idNamespace,
            String displayName,
            String errorPrefix) {
        this.template = template;
        this.principalIdNamespace = idNamespace + "/principal/";
        this.profileIdNamespace = idNamespace + "/profile/";
        this.displayName = displayName;
        this.errorPrefix = errorPrefix;
    }

    public static DistillerInitializationContract knowledge() {
        return new DistillerInitializationContract(
                DistillerTemplateContract.knowledge(),
                "io.crewscope/knowledge-distiller",
                "CrewScope Knowledge Distiller",
                "knowledgeDistiller");
    }

    public static DistillerInitializationContract skill() {
        return new DistillerInitializationContract(
                DistillerTemplateContract.skill(),
                "io.crewscope/skill-distiller",
                "CrewScope Skill Distiller",
                "skillDistiller");
    }

    /** Creates a deterministic disabled Distiller pair without guessing a model configuration. */
    public DistillerInitializationPair createDefault(
            Team team,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            AgentTemplateDefinition templateDefinition,
            UtcTimestamp occurredAt) {
        Team requiredTeam = requireActiveTeam(team);
        Workspace requiredWorkspace = requireDefaultWorkspace(requiredTeam, workspace);
        Principal requiredOwner = requireOwner(requiredTeam, ownerMember, ownerUser);
        AgentTemplateDefinition requiredTemplate =
                template.requireDefinition(templateDefinition);
        if (!requiredTemplate.publisherScope().organizationId()
                .equals(requiredTeam.organizationId())) {
            throw new DomainValidationException(
                    errorPrefix + ".template", "must belong to the Team Organization");
        }
        UtcTimestamp now = Objects.requireNonNull(occurredAt, "occurredAt");
        Principal provisionedPrincipal = Principal.create(
                stablePrincipalId(requiredTeam.id()),
                PrincipalScope.team(requiredTeam.organizationId(), requiredTeam.id()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(requiredOwner.id()),
                displayName,
                Optional.empty(),
                PrincipalVisibility.TEAM,
                now);
        AgentProfile profile = AgentProfile.createTemplateInstance(
                        stableProfileId(requiredTeam.id()),
                        requiredWorkspace,
                        provisionedPrincipal,
                        AgentOwnership.team(requiredTeam.organizationId(), requiredTeam.id()),
                        requiredTemplate,
                        false,
                        requiredOwner.id(),
                        now)
                .disable(requiredOwner.id(), now);
        return new DistillerInitializationPair(
                provisionedPrincipal.transitionTo(PrincipalStatus.DISABLED, now), profile);
    }

    /** Activates the synchronized pair only after an exact current TEAM configuration exists. */
    public DistillerInitializationPair activate(
            Principal agentPrincipal,
            AgentProfile agentProfile,
            AgentConfigurationVersion configuration,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        requireActivationConfiguration(agentPrincipal, agentProfile, configuration);
        UtcTimestamp now = Objects.requireNonNull(occurredAt, "occurredAt");
        PrincipalId requiredActor = Objects.requireNonNull(actor, "actor");
        return new DistillerInitializationPair(
                agentPrincipal.transitionTo(PrincipalStatus.ACTIVE, now),
                agentProfile.activate(requiredActor, now));
    }

    /** Validates configuration coordinates before model Preflight performs any external lookup. */
    public AgentConfigurationVersion requireActivationConfiguration(
            Principal agentPrincipal,
            AgentProfile agentProfile,
            AgentConfigurationVersion configuration) {
        AgentConfigurationVersion required = Objects.requireNonNull(configuration, "configuration");
        if (agentPrincipal.status() != PrincipalStatus.DISABLED
                || agentProfile.status() != AgentProfileStatus.DISABLED) {
            throw new DomainValidationException(
                    errorPrefix + ".status", "must be DISABLED before explicit activation");
        }
        if (!required.organizationId().equals(agentProfile.scope().organizationId())
                || !required.agentProfileId().equals(agentProfile.id())
                || !required.ownership().equals(agentProfile.ownership())
                || !required.templateVersion().equals(template.version())
                || required.personalModelBinding().isPresent()
                || required.teamModelBinding().isEmpty()
                || required.teamModelBinding().orElseThrow().executionScope()
                        != AgentExecutionScope.TEAM) {
            throw new DomainValidationException(
                    errorPrefix + ".configuration",
                    "must be the current TEAM-only configuration of this Distiller");
        }
        return required;
    }

    /** Fails closed when persistence returns another Team's deterministic Distiller pair. */
    public void requireDefaultFor(
            Principal agentPrincipal, AgentProfile agentProfile, Team team, Workspace workspace) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        Workspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (!agentPrincipal.id().equals(stablePrincipalId(requiredTeam.id()))
                || !agentProfile.id().equals(stableProfileId(requiredTeam.id()))
                || !agentProfile.workspaceId().equals(requiredWorkspace.id())
                || !agentProfile.scope().equals(requiredWorkspace.scope())
                || agentProfile.ownership().teamId().filter(requiredTeam.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    errorPrefix + ".initialization",
                    "must be the deterministic Distiller of the expected Team Workspace");
        }
    }

    public PrincipalId stablePrincipalId(TeamId teamId) {
        return new PrincipalId(stableUuid(principalIdNamespace, teamId));
    }

    public AgentProfileId stableProfileId(TeamId teamId) {
        return new AgentProfileId(stableUuid(profileIdNamespace, teamId));
    }

    private static UUID stableUuid(String namespace, TeamId teamId) {
        String source = namespace + Objects.requireNonNull(teamId, "teamId");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private Team requireActiveTeam(Team team) {
        Team required = Objects.requireNonNull(team, "team");
        if (!required.isActive()) {
            throw new DomainValidationException(
                    errorPrefix + ".teamId", "must reference an active Team");
        }
        return required;
    }

    private Workspace requireDefaultWorkspace(Team team, Workspace workspace) {
        Workspace required = Objects.requireNonNull(workspace, "workspace");
        if (required.type() != WorkspaceType.TEAM
                || required.status() != WorkspaceStatus.ACTIVE
                || !required.id().equals(team.defaultWorkspaceId())
                || !required.scope().organizationId().equals(team.organizationId())
                || required.scope().teamId().filter(team.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    errorPrefix + ".workspaceId", "must be the active default Team Workspace");
        }
        return required;
    }

    private Principal requireOwner(Team team, TeamMember ownerMember, Principal ownerUser) {
        TeamMember member = Objects.requireNonNull(ownerMember, "ownerMember");
        Principal user = Objects.requireNonNull(ownerUser, "ownerUser");
        if (!team.isOwner(member.id())
                || !member.canParticipate()
                || !member.scope().equals(team.scope())
                || !member.userPrincipalId().equals(user.id())
                || user.type() != PrincipalType.USER
                || !user.canAct()
                || !user.scope().organizationId().equals(team.organizationId())) {
            throw new DomainValidationException(
                    errorPrefix + ".ownerMemberId", "must be the active Team owner USER");
        }
        return user;
    }

    /** Fails closed when the Principal/Profile pair is not synchronized. */
    public void validatePair(Principal principal, AgentProfile profile) {
        template.requireProfile(profile);
        PrincipalStatus expectedPrincipalStatus = switch (profile.status()) {
            case ACTIVE -> PrincipalStatus.ACTIVE;
            case DISABLED -> PrincipalStatus.DISABLED;
            case ARCHIVED -> PrincipalStatus.ARCHIVED;
        };
        if (principal.type() != PrincipalType.SPECIALIST_AGENT
                || principal.visibility() != PrincipalVisibility.TEAM
                || principal.ownerPrincipalId().isEmpty()
                || principal.status() != expectedPrincipalStatus
                || !profile.agentPrincipalId().equals(principal.id())
                || !profile.scope().organizationId().equals(principal.scope().organizationId())
                || !profile.scope().teamId().equals(principal.scope().teamId())) {
            throw new DomainValidationException(
                    errorPrefix + ".initialization",
                    "must contain one synchronized Team Agent Principal/Profile pair");
        }
    }
}
