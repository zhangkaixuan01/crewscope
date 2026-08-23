package io.crewscope.domain.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentProfileTemplateTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-22T16:00:00Z");

    @Test
    void createsUserAndTeamOwnedSpecialistsFromTheSameTemplate() {
        Fixture fixture = Fixture.create();
        AgentTemplateDefinition coding = fixture.template("coding");
        AgentTemplateDefinition reviewer = fixture.template("reviewer");
        Principal personalCoding = fixture.specialist(
                "Personal Coding", PrincipalVisibility.PRIVATE);
        Principal teamCoding = fixture.specialist("Team Coding", PrincipalVisibility.TEAM);
        Principal personalReviewer = fixture.specialist(
                "Personal Reviewer", PrincipalVisibility.PRIVATE);

        AgentProfile personalCodingProfile = AgentProfile.createTemplateInstance(
                AgentProfileId.generate(),
                fixture.team.defaultWorkspace(),
                personalCoding,
                fixture.userOwnership(),
                coding,
                false,
                fixture.owner.id(),
                CREATED_AT);
        AgentProfile teamCodingProfile = AgentProfile.createTemplateInstance(
                AgentProfileId.generate(),
                fixture.team.defaultWorkspace(),
                teamCoding,
                fixture.teamOwnership(),
                coding,
                false,
                fixture.owner.id(),
                CREATED_AT);
        AgentProfile reviewerProfile = AgentProfile.createTemplateInstance(
                AgentProfileId.generate(),
                fixture.team.defaultWorkspace(),
                personalReviewer,
                fixture.userOwnership(),
                reviewer,
                false,
                fixture.owner.id(),
                CREATED_AT);

        assertEquals(AgentOwnershipType.USER, personalCodingProfile.ownership().type());
        assertEquals(AgentOwnershipType.TEAM, teamCodingProfile.ownership().type());
        assertEquals(AgentRuntimeRole.SPECIALIST, personalCodingProfile.runtimeRole());
        assertEquals(personalCodingProfile.runtimeRole(), teamCodingProfile.runtimeRole());
        assertEquals(coding.templateVersion(), personalCodingProfile.templateVersion());
        assertEquals(coding.templateVersion(), teamCodingProfile.templateVersion());
        assertEquals(reviewer.templateVersion(), reviewerProfile.templateVersion());
        assertFalse(personalCodingProfile.defaultProfile());
        assertFalse(teamCodingProfile.defaultProfile());
    }

    @Test
    void disabledTemplateCannotCreateAnotherProfileButExistingProfileKeepsExactVersion() {
        Fixture fixture = Fixture.create();
        AgentTemplateDefinition active = fixture.template("coding");
        Principal firstPrincipal = fixture.specialist(
                "First Coding", PrincipalVisibility.PRIVATE);
        AgentProfile historical = AgentProfile.createTemplateInstance(
                AgentProfileId.generate(),
                fixture.team.defaultWorkspace(),
                firstPrincipal,
                fixture.userOwnership(),
                active,
                false,
                fixture.owner.id(),
                CREATED_AT);
        AgentTemplateDefinition disabled = active.disable(
                fixture.owner.id(), UtcTimestamp.parse("2026-08-22T16:01:00Z"));

        assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.createTemplateInstance(
                        AgentProfileId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.specialist("Second Coding", PrincipalVisibility.PRIVATE),
                        fixture.userOwnership(),
                        disabled,
                        false,
                        fixture.owner.id(),
                        UtcTimestamp.parse("2026-08-22T16:02:00Z")));
        assertEquals(AgentTemplateVersion.of("coding", 1), historical.templateVersion());
        assertEquals(AgentProfileStatus.ACTIVE, historical.status());
    }

    @Test
    void onlyUserOwnedPersonalAssistantCanBeDefault() {
        Fixture fixture = Fixture.create();
        AgentTemplateDefinition coding = fixture.template("coding");

        assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.createTemplateInstance(
                        AgentProfileId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.specialist("Default Coding", PrincipalVisibility.PRIVATE),
                        fixture.userOwnership(),
                        coding,
                        true,
                        fixture.owner.id(),
                        CREATED_AT));

        AgentProfile defaultPersonal = fixture.team.ownerPersonalAgent().agentProfile();
        assertTrue(defaultPersonal.isActiveDefaultPersonal());
        assertEquals(AgentOwnershipType.USER, defaultPersonal.ownership().type());
        assertEquals(AgentRuntimeRole.PERSONAL_ASSISTANT, defaultPersonal.runtimeRole());
        assertEquals(
                AgentTemplateVersion.of("personal-assistant", 1),
                defaultPersonal.templateVersion());
    }

    @Test
    void genericTemplateCreationCannotBypassAtomicDefaultPersonalInitialization() {
        Fixture fixture = Fixture.create();
        AgentTemplateDefinition personalTemplate = AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(ORGANIZATION_ID),
                new AgentTemplateKey("personal-assistant"),
                AgentRuntimeRole.PERSONAL_ASSISTANT,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("conversation.orchestrate")),
                        Set.of()),
                AgentTemplatePolicy.define(
                        "Assist the member and orchestrate approved tasks.",
                        Set.of(),
                        Set.of(),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                fixture.owner.id(),
                CREATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.createTemplateInstance(
                        AgentProfileId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.team.ownerPersonalAgent().agentPrincipal(),
                        fixture.userOwnership(),
                        personalTemplate,
                        true,
                        fixture.owner.id(),
                        CREATED_AT));
        assertTrue(fixture.team.ownerPersonalAgent().agentProfile().isActiveDefaultPersonal());
    }

    @Test
    void rejectsPrincipalVisibilityOrLegacyTypeThatDisagreesWithExplicitOwnershipAndRole() {
        Fixture fixture = Fixture.create();
        AgentTemplateDefinition coding = fixture.template("coding");

        assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.createTemplateInstance(
                        AgentProfileId.generate(),
                        fixture.team.defaultWorkspace(),
                        fixture.specialist("Forged Team Coding", PrincipalVisibility.TEAM),
                        fixture.userOwnership(),
                        coding,
                        false,
                        fixture.owner.id(),
                        CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> AgentProfile.reconstituteTemplateInstance(
                        AgentProfileId.generate(),
                        fixture.team.defaultWorkspace().scope(),
                        fixture.team.defaultWorkspace().id(),
                        PrincipalId.generate(),
                        fixture.userOwnership(),
                        AgentRuntimeRole.SPECIALIST,
                        AgentTemplateVersion.of("coding", 1),
                        AgentProfileType.TEAM,
                        false,
                        AgentProfileStatus.ACTIVE,
                        0,
                        AuditMetadata.createdBy(fixture.owner.id(), CREATED_AT)));
    }

    @Test
    void deterministicallyProjectsLegacyProfilesWithoutChangingStableFacts() {
        Fixture fixture = Fixture.create();
        AgentProfileId profileId = AgentProfileId.generate();
        PrincipalId agentPrincipalId = PrincipalId.generate();
        TeamMemberId ownerMemberId = fixture.team.ownerMember().id();
        AuditMetadata audit = AuditMetadata.createdBy(fixture.owner.id(), CREATED_AT);

        AgentProfile personal = AgentProfile.reconstitute(
                profileId,
                fixture.team.defaultWorkspace().scope(),
                fixture.team.defaultWorkspace().id(),
                agentPrincipalId,
                Optional.of(ownerMemberId),
                AgentProfileType.PERSONAL,
                true,
                AgentProfileStatus.ACTIVE,
                7,
                audit);
        AgentProfile personalSpecialist = AgentProfile.reconstitute(
                AgentProfileId.generate(),
                fixture.team.defaultWorkspace().scope(),
                fixture.team.defaultWorkspace().id(),
                PrincipalId.generate(),
                Optional.of(ownerMemberId),
                AgentProfileType.SPECIALIST,
                false,
                AgentProfileStatus.ACTIVE,
                3,
                audit);
        AgentProfile teamSpecialist = AgentProfile.reconstitute(
                AgentProfileId.generate(),
                fixture.team.defaultWorkspace().scope(),
                fixture.team.defaultWorkspace().id(),
                PrincipalId.generate(),
                Optional.empty(),
                AgentProfileType.SPECIALIST,
                false,
                AgentProfileStatus.ACTIVE,
                2,
                audit);

        assertEquals(profileId, personal.id());
        assertEquals(agentPrincipalId, personal.agentPrincipalId());
        assertEquals(7, personal.version());
        assertEquals(AgentRuntimeRole.PERSONAL_ASSISTANT, personal.runtimeRole());
        assertEquals(AgentTemplateVersion.of("personal-assistant", 1),
                personal.templateVersion());
        assertEquals(AgentOwnershipType.USER, personalSpecialist.ownership().type());
        assertEquals(AgentOwnershipType.TEAM, teamSpecialist.ownership().type());
        assertEquals(AgentTemplateVersion.of("coding", 1),
                personalSpecialist.templateVersion());
        assertEquals(personalSpecialist.templateVersion(), teamSpecialist.templateVersion());
    }

    private record Fixture(Principal owner, TeamInitialization team) {

        private static Fixture create() {
            Principal owner = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(ORGANIZATION_ID),
                    PrincipalType.USER,
                    Optional.empty(),
                    "M5 owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    CREATED_AT);
            return new Fixture(
                    owner, TeamInitialization.create(owner, "M5 team", CREATED_AT));
        }

        private AgentTemplateDefinition template(String key) {
            return AgentTemplateDefinition.publishInitial(
                    AgentTemplatePublisherScope.organization(ORGANIZATION_ID),
                    new AgentTemplateKey(key),
                    AgentRuntimeRole.SPECIALIST,
                    Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM),
                    Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                    AgentTemplateCapabilities.define(
                            Set.of(new AgentTemplateCapability(key + ".execute")),
                            Set.of(new AgentTemplateCapability("model.tool-calling"))),
                    AgentTemplatePolicy.define(
                            "Execute the approved " + key + " workflow.",
                            Set.of(new AgentToolKey(key + ".inspect")),
                            Set.of(key + "-baseline"),
                            Optional.of("{\"type\":\"object\"}"),
                            Set.of(
                                    AgentConfigurableSlot.DISPLAY_NAME,
                                    AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS),
                            Set.of(
                                    AgentConfigurableSlot.MODEL_BINDING,
                                    AgentConfigurableSlot.BUDGET)),
                    owner.id(),
                    CREATED_AT);
        }

        private AgentOwnership userOwnership() {
            return AgentOwnership.user(
                    ORGANIZATION_ID, team.team().id(), team.ownerMember().id());
        }

        private AgentOwnership teamOwnership() {
            return AgentOwnership.team(ORGANIZATION_ID, team.team().id());
        }

        private Principal specialist(String name, PrincipalVisibility visibility) {
            return Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(ORGANIZATION_ID, team.team().id()),
                    PrincipalType.SPECIALIST_AGENT,
                    Optional.of(owner.id()),
                    name,
                    Optional.empty(),
                    visibility,
                    CREATED_AT);
        }
    }
}
