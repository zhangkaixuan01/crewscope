package io.crewscope.application.skill;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.distiller.DistillerProvisioningTemplate;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;

/**
 * Lazily provisions one Team's built-in Skill Distiller and activates it when a safe
 * TEAM model is selectable (M10-A03b). The stage sequence is shared with the Knowledge
 * Distiller (M13-C03); this service keeps the historical constructor and readiness API.
 */
public final class SkillDistillerProvisioningService implements SkillDistillerReadiness {

    private final DistillerProvisioningTemplate<SkillDistillerInitialization> provisioning;

    public SkillDistillerProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            SkillDistillerRepository distillers,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.provisioning = DistillerProvisioningTemplate.skill(
                teams,
                workspaces,
                members,
                principals,
                templateCatalog,
                templates,
                distillers,
                configurations,
                configurationService,
                transactions,
                timeProvider);
    }

    /** Ensures identity, initial model configuration and ACTIVE lifecycle for one Team. */
    @Override
    public void ensureReady(OrganizationId organizationId, TeamId teamId) {
        provisioning.ensureReady(organizationId, teamId);
    }
}
