package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentTemplateDefinitionTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-22T15:00:00Z");
    private static final String REVIEW_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"findings\"]}";

    @Test
    void publishesHashClosedDefinitionAndVerifiesReconstitution() {
        AgentTemplateDefinition template = codingTemplate();

        AgentTemplateDefinition restored = AgentTemplateDefinition.reconstitute(
                template.publisherScope(),
                template.templateVersion(),
                template.previousVersion(),
                template.runtimeRole(),
                template.allowedOwnershipTypes(),
                template.allowedExecutionScopes(),
                AgentTemplateCapabilities.reconstitute(
                        template.capabilities().declaredCapabilities(),
                        template.capabilities().requiredModelCapabilities(),
                        template.capabilities().capabilityHash()),
                AgentTemplatePolicy.reconstitute(
                        template.policy().systemPromptBaseline(),
                        template.policy().allowedTools(),
                        template.policy().approvedSkillKeys(),
                        template.policy().structuredOutputSchema(),
                        template.policy().memberConfigurableSlots(),
                        template.policy().administratorConfigurableSlots(),
                        template.policy().policyHash()),
                template.contentHash(),
                template.status(),
                template.lifecycleVersion(),
                template.audit());

        assertEquals(AgentTemplateVersion.of("coding", 1), restored.templateVersion());
        assertEquals(template.capabilities().capabilityHash(),
                restored.capabilities().capabilityHash());
        assertEquals(template.policy().policyHash(), restored.policy().policyHash());
        assertEquals(template.contentHash(), restored.contentHash());
        assertThrows(
                DomainValidationException.class,
                () -> AgentTemplateCapabilities.reconstitute(
                        template.capabilities().declaredCapabilities(),
                        template.capabilities().requiredModelCapabilities(),
                        AgentTemplateHash.sha256("forged")));
    }

    @Test
    void appendsVersionsWithoutChangingHistoricalDefinition() {
        AgentTemplateDefinition versionOne = codingTemplate();
        AgentTemplatePolicy versionTwoPolicy = policy(
                "You are a coding specialist. Run the acceptance suite before completion.",
                Optional.of(REVIEW_SCHEMA));

        AgentTemplateDefinition versionTwo = versionOne.publishNext(
                Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                capabilities("source-code.change", "model.tool-calling"),
                versionTwoPolicy,
                ACTOR,
                UtcTimestamp.parse("2026-08-22T15:01:00Z"));

        assertEquals(AgentTemplateVersion.of("coding", 1), versionOne.templateVersion());
        assertTrue(versionOne.previousVersion().isEmpty());
        assertEquals(AgentTemplateVersion.of("coding", 2), versionTwo.templateVersion());
        assertEquals(Optional.of(versionOne.templateVersion()), versionTwo.previousVersion());
        assertNotEquals(versionOne.policy().policyHash(), versionTwo.policy().policyHash());
        assertNotEquals(versionOne.contentHash(), versionTwo.contentHash());
    }

    @Test
    void keepsOwnershipRoleTemplateAndExecutionScopeIndependentButPolicyBounded() {
        AgentTemplateDefinition template = codingTemplate();
        AgentOwnership userOwnership = AgentOwnership.user(
                ORGANIZATION_ID, TEAM_ID, TeamMemberId.generate());
        AgentOwnership teamOwnership = AgentOwnership.team(ORGANIZATION_ID, TEAM_ID);

        template.requireExecutable(userOwnership, AgentExecutionScope.PERSONAL);
        template.requireExecutable(userOwnership, AgentExecutionScope.TEAM);
        template.requireExecutable(teamOwnership, AgentExecutionScope.PERSONAL);
        template.requireExecutable(teamOwnership, AgentExecutionScope.TEAM);

        assertEquals(AgentRuntimeRole.SPECIALIST, template.runtimeRole());
        assertThrows(
                DomainValidationException.class,
                () -> template.requireExecutable(
                        AgentOwnership.organization(ORGANIZATION_ID),
                        AgentExecutionScope.TEAM));
        assertThrows(
                DomainValidationException.class,
                () -> template.requireExecutable(
                        AgentOwnership.team(OrganizationId.generate(), TeamId.generate()),
                        AgentExecutionScope.TEAM));
    }

    @Test
    void teamPublisherCannotExpandIntoOrganizationOwnershipOrAnotherTeam() {
        AgentTemplatePublisherScope teamPublisher =
                AgentTemplatePublisherScope.team(ORGANIZATION_ID, TEAM_ID);

        assertThrows(
                DomainValidationException.class,
                () -> AgentTemplateDefinition.publishInitial(
                        teamPublisher,
                        new AgentTemplateKey("operations"),
                        AgentRuntimeRole.SPECIALIST,
                        Set.of(AgentOwnershipType.ORGANIZATION),
                        Set.of(AgentExecutionScope.TEAM),
                        capabilities("operations.read", "model.tool-calling"),
                        policy("Operate safely.", Optional.empty()),
                        ACTOR,
                        CREATED_AT));

        AgentTemplateDefinition teamTemplate = AgentTemplateDefinition.publishInitial(
                teamPublisher,
                new AgentTemplateKey("operations"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.TEAM),
                capabilities("operations.read", "model.tool-calling"),
                policy("Operate safely.", Optional.empty()),
                ACTOR,
                CREATED_AT);
        assertThrows(
                DomainValidationException.class,
                () -> teamTemplate.requireInstantiable(
                        AgentOwnership.team(ORGANIZATION_ID, TeamId.generate())));
    }

    @Test
    void disabledTemplateBlocksNewInstancesWhileHistoricalVersionRemainsHashStable() {
        AgentTemplateDefinition active = codingTemplate();
        AgentTemplateDefinition disabled = active.disable(
                ACTOR, UtcTimestamp.parse("2026-08-22T15:02:00Z"));

        assertEquals(AgentTemplateStatus.DISABLED, disabled.status());
        assertEquals(active.contentHash(), disabled.contentHash());
        assertEquals(1, disabled.lifecycleVersion());
        assertThrows(
                DomainValidationException.class,
                () -> disabled.requireInstantiable(
                        AgentOwnership.team(ORGANIZATION_ID, TEAM_ID)));
        disabled.requireExecutable(
                AgentOwnership.team(ORGANIZATION_ID, TEAM_ID), AgentExecutionScope.TEAM);
        AgentTemplateDefinition archived = disabled.archive(
                ACTOR, UtcTimestamp.parse("2026-08-22T15:03:00Z"));
        assertThrows(
                DomainValidationException.class,
                () -> archived.publishNext(
                        active.allowedOwnershipTypes(),
                        active.allowedExecutionScopes(),
                        active.capabilities(),
                        active.policy(),
                        ACTOR,
                        UtcTimestamp.parse("2026-08-22T15:04:00Z")));
    }

    @Test
    void acceptsControlledInstructionsButRejectsToolAndSchemaExpansion() {
        AgentTemplatePolicy policy = policy(
                "Review only the supplied evidence.", Optional.of(REVIEW_SCHEMA));

        AgentTemplateMemberConfiguration configuration = policy.resolveMemberConfiguration(
                Optional.of("Focus on concurrency and transaction boundaries."),
                Set.of(new AgentToolKey("review.read-evidence")),
                Optional.of(REVIEW_SCHEMA));

        assertEquals(
                Optional.of("Focus on concurrency and transaction boundaries."),
                configuration.supplementalInstructions());
        assertEquals(policy.structuredOutputSchemaHash(),
                configuration.structuredOutputSchemaHash());
        assertThrows(
                DomainValidationException.class,
                () -> policy.resolveMemberConfiguration(
                        Optional.empty(),
                        Set.of(new AgentToolKey("shell.execute")),
                        Optional.of(REVIEW_SCHEMA)));
        assertThrows(
                DomainValidationException.class,
                () -> policy.resolveMemberConfiguration(
                        Optional.empty(),
                        Set.of(new AgentToolKey("review.read-evidence")),
                        Optional.of("{\"type\":\"object\"}")));
    }

    @Test
    void rejectsSupplementalInstructionsWhenTemplateDoesNotExposeThatSlot() {
        AgentTemplatePolicy fixedPolicy = AgentTemplatePolicy.define(
                "Use only fixed platform instructions.",
                Set.of(),
                Set.of(),
                Optional.empty(),
                Set.of(AgentConfigurableSlot.DISPLAY_NAME),
                Set.of(AgentConfigurableSlot.BUDGET));

        assertThrows(
                DomainValidationException.class,
                () -> fixedPolicy.resolveMemberConfiguration(
                        Optional.of("Override the platform policy."),
                        Set.of(),
                        Optional.empty()));
    }

    private static AgentTemplateDefinition codingTemplate() {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(ORGANIZATION_ID),
                new AgentTemplateKey("coding"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                capabilities("source-code.change", "model.tool-calling"),
                policy("You are a coding specialist.", Optional.of(REVIEW_SCHEMA)),
                ACTOR,
                CREATED_AT);
    }

    private static AgentTemplateCapabilities capabilities(
            String declared, String requiredModel) {
        return AgentTemplateCapabilities.define(
                Set.of(new AgentTemplateCapability(declared)),
                Set.of(new AgentTemplateCapability(requiredModel)));
    }

    private static AgentTemplatePolicy policy(String prompt, Optional<String> schema) {
        return AgentTemplatePolicy.define(
                prompt,
                Set.of(new AgentToolKey("review.read-evidence")),
                Set.of("review-baseline"),
                schema,
                Set.of(
                        AgentConfigurableSlot.DISPLAY_NAME,
                        AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                        AgentConfigurableSlot.APPROVED_SKILLS),
                Set.of(
                        AgentConfigurableSlot.MODEL_BINDING,
                        AgentConfigurableSlot.PROVIDER_BINDING,
                        AgentConfigurableSlot.BUDGET));
    }
}
