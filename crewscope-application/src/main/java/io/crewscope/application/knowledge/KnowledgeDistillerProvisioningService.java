package io.crewscope.application.knowledge;

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
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;

/**
 * Lazily provisions one Team's built-in Knowledge Distiller and activates it when a safe
 * TEAM model is selectable (D10). The stage sequence is shared with the Skill Distiller
 * (M13-C03); this service keeps the historical constructor and return type.
 */
public final class KnowledgeDistillerProvisioningService {

    private final DistillerProvisioningTemplate<KnowledgeDistillerInitialization> provisioning;

    public KnowledgeDistillerProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            KnowledgeDistillerRepository distillers,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.provisioning = DistillerProvisioningTemplate.knowledge(
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
    public KnowledgeDistillerInitialization ensureReady(
            OrganizationId organizationId, TeamId teamId) {
        return provisioning.ensureReady(organizationId, teamId);
    }
}
