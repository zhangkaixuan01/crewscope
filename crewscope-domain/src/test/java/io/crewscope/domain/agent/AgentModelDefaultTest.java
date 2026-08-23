package io.crewscope.domain.agent;

import static io.crewscope.domain.agent.AgentConfigurationTestFixture.ACTOR;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.CREATED_AT;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.OTHER_TEAM_ID;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.OWNER_USER_ID;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.ORGANIZATION_ID;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.TEAM_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentModelDefaultTest {

    @Test
    void publishesTeamDefaultWithSameTeamAndOrganizationSelections() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentModelDefault modelDefault = AgentModelDefault.publishInitial(
                template,
                AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                AgentExecutionScope.TEAM,
                direct(
                        AgentConfigurationTestFixture.teamSelection(TEAM_ID, "default-team"),
                        AgentConfigurationTestFixture.organizationSelection("default-org")),
                policyPack(1),
                ACTOR,
                CREATED_AT);

        assertEquals(new AgentModelDefaultRevision(1), modelDefault.revision());
        assertEquals(Optional.empty(), modelDefault.previousRevision());
        assertEquals(template.templateVersion(), modelDefault.templateVersion());
        assertEquals(AgentExecutionScope.TEAM, modelDefault.executionScope());
    }

    @Test
    void rejectsUserForeignTeamAndTeamConnectionInOrganizationDefaults() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();

        assertThrows(
                DomainValidationException.class,
                () -> AgentModelDefault.publishInitial(
                        template,
                        AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                        AgentExecutionScope.TEAM,
                        direct(AgentConfigurationTestFixture.userSelection(
                                OWNER_USER_ID, "default-user"), null),
                        policyPack(1),
                        ACTOR,
                        CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> AgentModelDefault.publishInitial(
                        template,
                        AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                        AgentExecutionScope.TEAM,
                        direct(AgentConfigurationTestFixture.teamSelection(
                                OTHER_TEAM_ID, "default-other-team"), null),
                        policyPack(1),
                        ACTOR,
                        CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> AgentModelDefault.publishInitial(
                        template,
                        AgentModelDefaultScope.organization(ORGANIZATION_ID),
                        AgentExecutionScope.TEAM,
                        direct(AgentConfigurationTestFixture.teamSelection(
                                TEAM_ID, "organization-team"), null),
                        policyPack(1),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void appendsAndReconstitutesHashVerifiedDefaultRevisions() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentModelDefault first = AgentModelDefault.publishInitial(
                template,
                AgentModelDefaultScope.organization(ORGANIZATION_ID),
                AgentExecutionScope.PERSONAL,
                direct(AgentConfigurationTestFixture.organizationSelection("default-v1"), null),
                policyPack(1),
                ACTOR,
                CREATED_AT);
        AgentModelDefault second = first.publishNext(
                template,
                direct(AgentConfigurationTestFixture.organizationSelection("default-v2"), null),
                policyPack(2),
                ACTOR,
                UtcTimestamp.parse("2026-08-23T02:03:00Z"));

        assertEquals(new AgentModelDefaultRevision(2), second.revision());
        assertEquals(Optional.of(first.revision()), second.previousRevision());
        assertNotEquals(first.contentHash(), second.contentHash());
        AgentModelDefault restored = AgentModelDefault.reconstitute(
                template,
                second.scope(),
                second.executionScope(),
                second.revision(),
                second.previousRevision(),
                second.modelBinding(),
                second.policyPack(),
                second.contentHash(),
                second.audit());
        assertEquals(second.contentHash(), restored.contentHash());
        assertThrows(
                DomainValidationException.class,
                () -> AgentModelDefault.reconstitute(
                        template,
                        second.scope(),
                        second.executionScope(),
                        second.revision(),
                        second.previousRevision(),
                        second.modelBinding(),
                        second.policyPack(),
                        new AgentConfigurationHash("f".repeat(64)),
                        second.audit()));
    }

    private static AgentDirectModelBinding direct(
            AgentModelSelection primary, AgentModelSelection fallback) {
        return new AgentDirectModelBinding(primary, Optional.ofNullable(fallback));
    }

    private static PolicyPackReference policyPack(long version) {
        return new PolicyPackReference(PolicyPackId.generate(), version);
    }
}
