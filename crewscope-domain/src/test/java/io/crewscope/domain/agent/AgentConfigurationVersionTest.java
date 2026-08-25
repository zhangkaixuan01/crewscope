package io.crewscope.domain.agent;

import static io.crewscope.domain.agent.AgentConfigurationTestFixture.ACTOR;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.CREATED_AT;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.OTHER_TEAM_ID;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.OTHER_USER_ID;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.OWNER_USER_ID;
import static io.crewscope.domain.agent.AgentConfigurationTestFixture.TEAM_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class AgentConfigurationVersionTest {

    @Test
    void createsHashClosedPersonalAndTeamBindingsWithControlledConfiguration() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        AgentDirectModelBinding personal = direct(
                AgentConfigurationTestFixture.userSelection(OWNER_USER_ID, "personal-primary"),
                AgentConfigurationTestFixture.organizationSelection("personal-fallback"));
        AgentDirectModelBinding team = direct(
                AgentConfigurationTestFixture.teamSelection(TEAM_ID, "team-primary"),
                AgentConfigurationTestFixture.organizationSelection("team-fallback"));
        SafeModelGenerateOptions options = new SafeModelGenerateOptions(
                Optional.of(new BigDecimal("0.30")),
                Optional.of(new BigDecimal("0.9")),
                Optional.of(4_096L),
                AgentReasoningMode.ENABLED,
                true,
                true,
                Optional.of(42L),
                3);
        AgentMemoryPolicyReference memory =
                new AgentMemoryPolicyReference(UUID.randomUUID(), 2);
        AgentBudgetPolicyReference budget =
                new AgentBudgetPolicyReference(UUID.randomUUID(), 4);

        AgentConfigurationVersion configuration = AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.of(OWNER_USER_ID),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL, personal)),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.TEAM, team)),
                Optional.of("Focus on transactional boundaries."),
                Set.of("secure-coding", "java-review"),
                Optional.of(memory),
                Optional.of(budget),
                policyPack(3),
                options,
                ACTOR,
                CREATED_AT);

        assertEquals(new AgentConfigurationRevision(1), configuration.revision());
        assertTrue(configuration.previousRevision().isEmpty());
        assertEquals(profile.id(), configuration.agentProfileId());
        assertEquals(template.templateVersion(), configuration.templateVersion());
        assertEquals(template.contentHash(), configuration.templateContentHash());
        assertEquals(Set.of("secure-coding", "java-review"),
                configuration.approvedSkillKeys());
        assertEquals(Optional.of(memory), configuration.memoryPolicy());
        assertEquals(Optional.of(budget), configuration.budgetPolicy());
        assertEquals(options, configuration.generateOptions());
        assertEquals(
                Optional.of("Focus on transactional boundaries."),
                configuration.templateConfiguration().supplementalInstructions());
        assertFalse(configuration.configurationHash().value().isBlank());
    }

    @Test
    void appendsConsecutiveRevisionsAndRejectsHashOrCoordinateTampering() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        AgentConfigurationVersion first = basicUserConfiguration(profile, template);

        AgentConfigurationVersion second = first.appendNext(
                profile,
                template,
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL,
                        direct(AgentConfigurationTestFixture.organizationSelection("next"), null))),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of("java-review"),
                Optional.empty(),
                Optional.empty(),
                policyPack(4),
                SafeModelGenerateOptions.defaults(),
                ACTOR,
                UtcTimestamp.parse("2026-08-23T02:02:00Z"));

        assertEquals(new AgentConfigurationRevision(1), first.revision());
        assertEquals(new AgentConfigurationRevision(2), second.revision());
        assertEquals(Optional.of(first.revision()), second.previousRevision());
        assertNotEquals(first.configurationHash(), second.configurationHash());

        AgentConfigurationVersion restored = AgentConfigurationVersion.reconstitute(
                profile,
                template,
                second.ownerUserPrincipalId(),
                second.revision(),
                second.previousRevision(),
                second.personalModelBinding(),
                second.teamModelBinding(),
                second.templateConfiguration(),
                second.approvedSkillKeys(),
                second.memoryPolicy(),
                second.budgetPolicy(),
                second.policyPack(),
                second.generateOptions(),
                second.configurationHash(),
                second.audit());
        assertEquals(second.configurationHash(), restored.configurationHash());
        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.reconstitute(
                        profile,
                        template,
                        second.ownerUserPrincipalId(),
                        second.revision(),
                        second.previousRevision(),
                        second.personalModelBinding(),
                        second.teamModelBinding(),
                        second.templateConfiguration(),
                        second.approvedSkillKeys(),
                        second.memoryPolicy(),
                        second.budgetPolicy(),
                        second.policyPack(),
                        second.generateOptions(),
                        new AgentConfigurationHash("0".repeat(64)),
                        second.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.reconstitute(
                        profile,
                        template,
                        second.ownerUserPrincipalId(),
                        second.revision(),
                        second.previousRevision(),
                        second.personalModelBinding(),
                        second.teamModelBinding(),
                        new AgentTemplateMemberConfiguration(
                                second.templateConfiguration().supplementalInstructions(),
                                Set.of(new AgentToolKey("shell.execute")),
                                second.templateConfiguration().structuredOutputSchemaHash()),
                        second.approvedSkillKeys(),
                        second.memoryPolicy(),
                        second.budgetPolicy(),
                        second.policyPack(),
                        second.generateOptions(),
                        second.configurationHash(),
                        second.audit()));
    }

    @Test
    void keepsPersonalAndTeamBindingsIsolatedAndFailClosed() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        AgentDirectModelBinding personal = direct(
                AgentConfigurationTestFixture.userSelection(OWNER_USER_ID, "isolated"), null);

        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.createInitial(
                        profile,
                        template,
                        Optional.of(OWNER_USER_ID),
                        Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                        Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        policyPack(1),
                        SafeModelGenerateOptions.defaults(),
                        ACTOR,
                        CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.createInitial(
                        profile,
                        template,
                        Optional.of(OWNER_USER_ID),
                        Optional.of(AgentExecutionModelBinding.direct(
                                AgentExecutionScope.PERSONAL, personal)),
                        Optional.empty(),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        policyPack(1),
                        SafeModelGenerateOptions.defaults(),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void forbidsForeignUserConnectionsAndAnyUserConnectionInTeamExecution() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        AgentExecutionModelBinding validTeam = AgentExecutionModelBinding.direct(
                AgentExecutionScope.TEAM,
                direct(AgentConfigurationTestFixture.teamSelection(TEAM_ID, "valid-team"), null));

        assertThrows(
                DomainValidationException.class,
                () -> configuration(
                        profile,
                        template,
                        direct(AgentConfigurationTestFixture.userSelection(
                                OTHER_USER_ID, "foreign-owner"), null),
                        validTeam));
        assertThrows(
                DomainValidationException.class,
                () -> configuration(
                        profile,
                        template,
                        direct(AgentConfigurationTestFixture.userSelection(
                                OWNER_USER_ID, "valid-owner"), null),
                        AgentExecutionModelBinding.direct(
                                AgentExecutionScope.TEAM,
                                direct(AgentConfigurationTestFixture.userSelection(
                                        OWNER_USER_ID, "team-user"), null))));
        assertThrows(
                DomainValidationException.class,
                () -> configuration(
                        profile,
                        template,
                        direct(
                                AgentConfigurationTestFixture.userSelection(
                                        OWNER_USER_ID, "primary-owner"),
                                AgentConfigurationTestFixture.userSelection(
                                        OTHER_USER_ID, "fallback-owner")),
                        validTeam));
    }

    @Test
    void enforcesTeamAndOrganizationOwnedConnectionMatrices() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile teamProfile = AgentConfigurationTestFixture.teamProfile(template);
        AgentProfile organizationProfile =
                AgentConfigurationTestFixture.organizationProfile(template);

        AgentConfigurationVersion teamConfiguration = AgentConfigurationVersion.createInitial(
                teamProfile,
                template,
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL,
                        direct(AgentConfigurationTestFixture.teamSelection(
                                TEAM_ID, "team-personal"), null))),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.TEAM,
                        direct(AgentConfigurationTestFixture.organizationSelection(
                                "team-org"), null))),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                policyPack(1),
                SafeModelGenerateOptions.defaults(),
                ACTOR,
                CREATED_AT);
        assertEquals(AgentOwnershipType.TEAM, teamConfiguration.ownership().type());

        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.createInitial(
                        teamProfile,
                        template,
                        Optional.empty(),
                        Optional.of(AgentExecutionModelBinding.direct(
                                AgentExecutionScope.PERSONAL,
                                direct(AgentConfigurationTestFixture.teamSelection(
                                        OTHER_TEAM_ID, "other-team"), null))),
                        Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        policyPack(1),
                        SafeModelGenerateOptions.defaults(),
                        ACTOR,
                        CREATED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.createInitial(
                        organizationProfile,
                        template,
                        Optional.empty(),
                        Optional.of(AgentExecutionModelBinding.direct(
                                AgentExecutionScope.PERSONAL,
                                direct(AgentConfigurationTestFixture.teamSelection(
                                        TEAM_ID, "org-team"), null))),
                        Optional.of(AgentExecutionModelBinding.direct(
                                AgentExecutionScope.TEAM,
                                direct(AgentConfigurationTestFixture.organizationSelection(
                                        "org-valid"), null))),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        policyPack(1),
                        SafeModelGenerateOptions.defaults(),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void forcesDefaultPersonalAgentToRemainOrchestrationOnlyForTeamScope() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.personalTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        AgentDirectModelBinding personal = direct(
                AgentConfigurationTestFixture.userSelection(OWNER_USER_ID, "assistant"), null);

        AgentConfigurationVersion configuration = AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.of(OWNER_USER_ID),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL, personal)),
                Optional.of(AgentExecutionModelBinding.orchestrationOnly()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                policyPack(1),
                SafeModelGenerateOptions.defaults(),
                ACTOR,
                CREATED_AT);

        assertEquals(
                AgentModelBindingKind.ORCHESTRATION_ONLY,
                configuration.teamModelBinding().orElseThrow().kind());
        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.createInitial(
                        profile,
                        template,
                        Optional.of(OWNER_USER_ID),
                        configuration.personalModelBinding(),
                        Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                        Optional.empty(),
                        Set.of(),
                        Optional.empty(),
                        Optional.empty(),
                        policyPack(1),
                        SafeModelGenerateOptions.defaults(),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void canonicalizesSafeOptionsAndRejectsUnsafeRangesOrUnapprovedSkills() {
        SafeModelGenerateOptions normalized = new SafeModelGenerateOptions(
                Optional.of(new BigDecimal("0.3000")),
                Optional.of(new BigDecimal("1.000")),
                Optional.of(2_048L),
                AgentReasoningMode.DEFAULT,
                false,
                false,
                Optional.empty(),
                2);
        assertEquals(new BigDecimal("0.3"), normalized.temperature().orElseThrow());
        assertEquals(new BigDecimal("1"), normalized.topP().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> new SafeModelGenerateOptions(
                        Optional.of(new BigDecimal("2.1")),
                        Optional.empty(),
                        Optional.empty(),
                        AgentReasoningMode.DEFAULT,
                        true,
                        false,
                        Optional.empty(),
                        1));

        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        assertThrows(
                DomainValidationException.class,
                () -> AgentConfigurationVersion.createInitial(
                        profile,
                        template,
                        Optional.of(OWNER_USER_ID),
                        basicPersonalBinding(),
                        Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                        Optional.empty(),
                        Set.of("shell-root"),
                        Optional.empty(),
                        Optional.empty(),
                        policyPack(1),
                        normalized,
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void modelSelectionAndConfigurationExposeNoCredentialOrConnectionOverrideFields() {
        Set<String> forbidden = Set.of(
                "credential", "secret", "apikey", "endpoint", "header", "baseurl", "adapter");
        for (Class<?> type : Set.of(
                AgentModelSelection.class,
                AgentDirectModelBinding.class,
                SafeModelGenerateOptions.class)) {
            assertTrue(Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(String::toLowerCase)
                    .noneMatch(name -> forbidden.stream().anyMatch(name::contains)));
        }
    }

    /** Stable M5-Q01 Owner, Scope, USER credential and configuration-forgery attack set. */
    @TestFactory
    Stream<DynamicTest> m5Q01BlocksOwnerScopeAndConfigurationSubstitutionAttackSet() {
        AgentTemplateDefinition template = AgentConfigurationTestFixture.specialistTemplate();
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        AgentExecutionModelBinding validTeam = AgentExecutionModelBinding.direct(
                AgentExecutionScope.TEAM,
                direct(AgentConfigurationTestFixture.teamSelection(TEAM_ID, "valid-team"), null));
        AgentConfigurationVersion valid = basicUserConfiguration(profile, template);
        List<NamedAttack> attacks = List.of(
                new NamedAttack("AS-01-FOREIGN-USER-PRIMARY", () -> assertThrows(
                        DomainValidationException.class,
                        () -> configuration(
                                profile,
                                template,
                                direct(AgentConfigurationTestFixture.userSelection(
                                        OTHER_USER_ID, "foreign-primary"), null),
                                validTeam))),
                new NamedAttack("AS-02-FOREIGN-USER-FALLBACK", () -> assertThrows(
                        DomainValidationException.class,
                        () -> configuration(
                                profile,
                                template,
                                direct(
                                        AgentConfigurationTestFixture.userSelection(
                                                OWNER_USER_ID, "owner-primary"),
                                        AgentConfigurationTestFixture.userSelection(
                                                OTHER_USER_ID, "foreign-fallback")),
                                validTeam))),
                new NamedAttack("AS-03-USER-KEY-TEAM-PRIMARY", () -> assertThrows(
                        DomainValidationException.class,
                        () -> configuration(
                                profile,
                                template,
                                direct(AgentConfigurationTestFixture.userSelection(
                                        OWNER_USER_ID, "personal"), null),
                                AgentExecutionModelBinding.direct(
                                        AgentExecutionScope.TEAM,
                                        direct(AgentConfigurationTestFixture.userSelection(
                                                OWNER_USER_ID, "team-primary"), null))))),
                new NamedAttack("AS-04-USER-KEY-TEAM-FALLBACK", () -> assertThrows(
                        DomainValidationException.class,
                        () -> configuration(
                                profile,
                                template,
                                direct(AgentConfigurationTestFixture.userSelection(
                                        OWNER_USER_ID, "personal"), null),
                                AgentExecutionModelBinding.direct(
                                        AgentExecutionScope.TEAM,
                                        direct(
                                                AgentConfigurationTestFixture.teamSelection(
                                                        TEAM_ID, "team-primary"),
                                                AgentConfigurationTestFixture.userSelection(
                                                        OWNER_USER_ID, "team-fallback")))))),
                new NamedAttack("AS-05-FOREIGN-TEAM-CONNECTION", () -> assertThrows(
                        DomainValidationException.class,
                        () -> AgentConfigurationVersion.createInitial(
                                AgentConfigurationTestFixture.teamProfile(template),
                                template,
                                Optional.empty(),
                                Optional.of(AgentExecutionModelBinding.direct(
                                        AgentExecutionScope.PERSONAL,
                                        direct(AgentConfigurationTestFixture.teamSelection(
                                                OTHER_TEAM_ID, "foreign-team"), null))),
                                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                                Optional.empty(),
                                Set.of(),
                                Optional.empty(),
                                Optional.empty(),
                                policyPack(1),
                                SafeModelGenerateOptions.defaults(),
                                ACTOR,
                                CREATED_AT))),
                new NamedAttack("AS-06-UNAPPROVED-SKILL", () -> assertThrows(
                        DomainValidationException.class,
                        () -> AgentConfigurationVersion.createInitial(
                                profile,
                                template,
                                Optional.of(OWNER_USER_ID),
                                basicPersonalBinding(),
                                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                                Optional.empty(),
                                Set.of("shell-root"),
                                Optional.empty(),
                                Optional.empty(),
                                policyPack(1),
                                SafeModelGenerateOptions.defaults(),
                                ACTOR,
                                CREATED_AT))),
                new NamedAttack("AS-07-TOOL-POLICY-FORGERY", () -> assertThrows(
                        DomainValidationException.class,
                        () -> AgentConfigurationVersion.reconstitute(
                                profile,
                                template,
                                valid.ownerUserPrincipalId(),
                                valid.revision(),
                                valid.previousRevision(),
                                valid.personalModelBinding(),
                                valid.teamModelBinding(),
                                new AgentTemplateMemberConfiguration(
                                        valid.templateConfiguration().supplementalInstructions(),
                                        Set.of(new AgentToolKey("shell.exec")),
                                        valid.templateConfiguration().structuredOutputSchemaHash()),
                                valid.approvedSkillKeys(),
                                valid.memoryPolicy(),
                                valid.budgetPolicy(),
                                valid.policyPack(),
                                valid.generateOptions(),
                                valid.configurationHash(),
                                valid.audit()))),
                new NamedAttack("AS-08-CONFIGURATION-HASH-FORGERY", () -> assertThrows(
                        DomainValidationException.class,
                        () -> AgentConfigurationVersion.reconstitute(
                                profile,
                                template,
                                valid.ownerUserPrincipalId(),
                                valid.revision(),
                                valid.previousRevision(),
                                valid.personalModelBinding(),
                                valid.teamModelBinding(),
                                valid.templateConfiguration(),
                                valid.approvedSkillKeys(),
                                valid.memoryPolicy(),
                                valid.budgetPolicy(),
                                valid.policyPack(),
                                valid.generateOptions(),
                                new AgentConfigurationHash("0".repeat(64)),
                                valid.audit()))));
        return attacks.stream().map(attack -> dynamicTest(
                attack.id(), attack.operation()::run));
    }

    private static AgentConfigurationVersion basicUserConfiguration(
            AgentProfile profile, AgentTemplateDefinition template) {
        return AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.of(OWNER_USER_ID),
                basicPersonalBinding(),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                policyPack(1),
                SafeModelGenerateOptions.defaults(),
                ACTOR,
                CREATED_AT);
    }

    private static Optional<AgentExecutionModelBinding> basicPersonalBinding() {
        return Optional.of(AgentExecutionModelBinding.direct(
                AgentExecutionScope.PERSONAL,
                direct(AgentConfigurationTestFixture.userSelection(
                        OWNER_USER_ID, "basic"), null)));
    }

    private static AgentConfigurationVersion configuration(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentDirectModelBinding personal,
            AgentExecutionModelBinding team) {
        return AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.of(OWNER_USER_ID),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL, personal)),
                Optional.of(team),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                policyPack(1),
                SafeModelGenerateOptions.defaults(),
                ACTOR,
                CREATED_AT);
    }

    private static AgentDirectModelBinding direct(
            AgentModelSelection primary, AgentModelSelection fallback) {
        return new AgentDirectModelBinding(primary, Optional.ofNullable(fallback));
    }

    private static PolicyPackReference policyPack(long version) {
        return new PolicyPackReference(PolicyPackId.generate(), version);
    }

    private record NamedAttack(String id, Runnable operation) {}
}
