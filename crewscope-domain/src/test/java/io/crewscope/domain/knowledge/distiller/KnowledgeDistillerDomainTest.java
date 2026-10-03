package io.crewscope.domain.knowledge.distiller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves the built-in knowledge-distiller@1 contract and its provisioning pair invariants. */
class KnowledgeDistillerDomainTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T09:00:00Z");

    private Principal owner;
    private TeamInitialization team;
    private AgentTemplateDefinition template;
    private KnowledgeDistillerInitialization distiller;

    @BeforeEach
    void setUp() {
        owner = activeUser("Owner");
        team = TeamInitialization.create(owner, "Platform", NOW);
        template = KnowledgeDistillerTemplate.create(ORGANIZATION_ID, owner.id(), NOW);
        distiller = KnowledgeDistillerInitialization.createDefault(
                team.team(), team.defaultWorkspace(), team.ownerMember(), owner, template, NOW);
    }

    @Test
    void definesExactTeamOwnedSingleTurnStructuredOutputSurface() {
        assertEquals(KnowledgeDistillerTemplate.VERSION, template.templateVersion());
        assertEquals(AgentRuntimeRole.SPECIALIST, template.runtimeRole());
        assertEquals(Set.of(AgentOwnershipType.TEAM), template.allowedOwnershipTypes());
        assertEquals(Set.of(AgentExecutionScope.TEAM), template.allowedExecutionScopes());
        assertTrue(template.policy().allowedTools().isEmpty());
        assertTrue(template.policy().approvedSkillKeys().isEmpty());
        assertTrue(template.policy().memberConfigurableSlots().isEmpty());
        String schema = template.policy().structuredOutputSchema().orElseThrow();
        assertTrue(schema.contains("\"title\""));
        assertTrue(schema.contains("\"content\""));
        assertTrue(schema.contains("\"suggestedCategory\""));
        // The schema enum can never drift from the domain category vocabulary.
        Arrays.stream(KnowledgeCategory.values())
                .forEach(value -> assertTrue(schema.contains("\"" + value.name() + "\"")));
    }

    @Test
    void createsStableDisabledTeamServicePrincipalAndProfile() {
        KnowledgeDistillerInitialization retry = KnowledgeDistillerInitialization.createDefault(
                team.team(), team.defaultWorkspace(), team.ownerMember(), owner, template,
                UtcTimestamp.parse("2026-10-02T09:01:00Z"));

        assertEquals(distiller.agentPrincipal().id(), retry.agentPrincipal().id());
        assertEquals(distiller.agentProfile().id(), retry.agentProfile().id());
        assertEquals(PrincipalType.SPECIALIST_AGENT, distiller.agentPrincipal().type());
        assertEquals(PrincipalVisibility.TEAM, distiller.agentPrincipal().visibility());
        assertEquals(PrincipalStatus.DISABLED, distiller.agentPrincipal().status());
        assertEquals(AgentProfileStatus.DISABLED, distiller.agentProfile().status());
        assertEquals(KnowledgeDistillerTemplate.VERSION, distiller.agentProfile().templateVersion());
        assertEquals(
                KnowledgeDistillerInitialization.stableProfileId(team.team().id()),
                distiller.agentProfile().id());
    }

    @Test
    void rejectsWidenedTemplateAndWrongTeamWorkspace() {
        AgentTemplateDefinition widened = AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(ORGANIZATION_ID),
                new AgentTemplateKey("knowledge-distiller"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.TEAM),
                template.capabilities(),
                AgentTemplatePolicy.define(
                        "A widened distiller.",
                        Set.of(new AgentToolKey("task.read")),
                        Set.of(),
                        Optional.of(KnowledgeDistillerTemplate.outputSchema()),
                        Set.of(),
                        Set.of()),
                owner.id(),
                NOW);
        Principal otherOwner = activeUser("Other owner");
        TeamInitialization other = TeamInitialization.create(otherOwner, "Other", NOW);

        assertThrows(
                DomainValidationException.class,
                () -> KnowledgeDistillerInitialization.createDefault(
                        team.team(), team.defaultWorkspace(), team.ownerMember(), owner,
                        widened, NOW));
        assertThrows(
                DomainValidationException.class,
                () -> KnowledgeDistillerInitialization.createDefault(
                        team.team(), other.defaultWorkspace(), team.ownerMember(), owner,
                        template, NOW));
        assertThrows(
                DomainValidationException.class,
                () -> KnowledgeDistillerTemplate.requireDefinition(widened));
    }

    @Test
    void configuresWhileDisabledAndActivatesOnlyWithTeamBinding() {
        AgentConfigurationVersion configuration = teamConfiguration(distiller, template);

        KnowledgeDistillerInitialization active = distiller.activate(
                configuration, owner.id(), UtcTimestamp.parse("2026-10-02T09:01:00Z"));

        assertTrue(configuration.personalModelBinding().isEmpty());
        assertEquals(
                AgentExecutionScope.TEAM,
                configuration.teamModelBinding().orElseThrow().executionScope());
        assertEquals(PrincipalStatus.ACTIVE, active.agentPrincipal().status());
        assertEquals(AgentProfileStatus.ACTIVE, active.agentProfile().status());
        assertThrows(
                DomainValidationException.class,
                () -> active.activate(configuration, owner.id(), NOW));
    }

    private static AgentConfigurationVersion teamConfiguration(
            KnowledgeDistillerInitialization initialization, AgentTemplateDefinition definition) {
        return AgentConfigurationVersion.createInitial(
                initialization.agentProfile(),
                definition,
                Optional.empty(),
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                new PolicyPackReference(PolicyPackId.generate(), 1),
                SafeModelGenerateOptions.defaults(),
                initialization.agentPrincipal().ownerPrincipalId().orElseThrow(),
                NOW);
    }

    private static Principal activeUser(String displayName) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                displayName,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }
}
