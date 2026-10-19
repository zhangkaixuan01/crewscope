package io.crewscope.domain.knowledge.distiller;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.distiller.DistillerInitializationContract;
import io.crewscope.domain.distiller.DistillerInitializationPair;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.Workspace;
import java.util.Objects;

/**
 * Stable Team Agent Principal/Profile pair for one Team's built-in Knowledge Distiller,
 * mirroring the SkillDistillerInitialization pattern (M10-A03b): provisioned lazily as a
 * DISABLED pair bound to the Team default Workspace, activated only once an exact
 * current TEAM configuration exists. The pair logic itself is shared (M13-C03); this
 * record keeps the historical type and API.
 */
public record KnowledgeDistillerInitialization(Principal agentPrincipal, AgentProfile agentProfile) {

    private static final DistillerInitializationContract CONTRACT =
            DistillerInitializationContract.knowledge();

    public KnowledgeDistillerInitialization {
        agentPrincipal = Objects.requireNonNull(agentPrincipal, "agentPrincipal");
        agentProfile = Objects.requireNonNull(agentProfile, "agentProfile");
        CONTRACT.validatePair(agentPrincipal, agentProfile);
    }

    /** Creates a deterministic disabled Distiller pair without guessing a model configuration. */
    public static KnowledgeDistillerInitialization createDefault(
            Team team,
            Workspace workspace,
            TeamMember ownerMember,
            Principal ownerUser,
            AgentTemplateDefinition template,
            UtcTimestamp occurredAt) {
        return of(CONTRACT.createDefault(
                team, workspace, ownerMember, ownerUser, template, occurredAt));
    }

    /** Activates the synchronized pair only after an exact current TEAM configuration exists. */
    public KnowledgeDistillerInitialization activate(
            AgentConfigurationVersion configuration,
            PrincipalId actor,
            UtcTimestamp occurredAt) {
        return of(CONTRACT.activate(
                agentPrincipal, agentProfile, configuration, actor, occurredAt));
    }

    /** Validates configuration coordinates before model Preflight performs any external lookup. */
    public AgentConfigurationVersion requireActivationConfiguration(
            AgentConfigurationVersion configuration) {
        return CONTRACT.requireActivationConfiguration(
                agentPrincipal, agentProfile, configuration);
    }

    /** Fails closed when persistence returns another Team's deterministic Distiller pair. */
    public KnowledgeDistillerInitialization requireDefaultFor(Team team, Workspace workspace) {
        CONTRACT.requireDefaultFor(agentPrincipal, agentProfile, team, workspace);
        return this;
    }

    public static PrincipalId stablePrincipalId(TeamId teamId) {
        return CONTRACT.stablePrincipalId(teamId);
    }

    public static AgentProfileId stableProfileId(TeamId teamId) {
        return CONTRACT.stableProfileId(teamId);
    }

    private static KnowledgeDistillerInitialization of(DistillerInitializationPair pair) {
        return new KnowledgeDistillerInitialization(pair.agentPrincipal(), pair.agentProfile());
    }
}
