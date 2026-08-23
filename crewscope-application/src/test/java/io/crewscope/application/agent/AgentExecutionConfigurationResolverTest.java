package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentModelBindingSource;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelConnectionOwnerType;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelDataRetentionMode;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentExecutionConfigurationResolverTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId OWNER_ID = PrincipalId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-23T04:00:00Z");
    private static final UtcTimestamp VERIFIED_AT =
            UtcTimestamp.parse("2026-08-23T04:01:00Z");
    private static final UtcTimestamp RESOLVED_AT =
            UtcTimestamp.parse("2026-08-23T04:02:00Z");
    private static final ModelRegion GLOBAL = new ModelRegion("global");

    private final AgentModelDefaultRepository defaults = mock(AgentModelDefaultRepository.class);
    private final ModelProviderDefinitionRepository providers =
            mock(ModelProviderDefinitionRepository.class);
    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
    private final ModelPriceScheduleRepository prices = mock(ModelPriceScheduleRepository.class);
    private final AgentExecutionConfigurationResolver resolver =
            new AgentExecutionConfigurationResolver(
                    defaults, providers, connections, catalogs, prices);

    private AgentTemplateDefinition template;
    private AgentProfile profile;
    private ModelBundle personalPrimary;
    private ModelBundle personalFallback;
    private ModelBundle teamPrimary;
    private ModelBundle organizationPrimary;
    private AgentConfigurationVersion configuration;

    @BeforeEach
    void setUp() {
        template = template();
        profile = profile(template);
        personalPrimary = bundle(ModelConnectionOwnerType.USER, "personal-primary");
        personalFallback = bundle(ModelConnectionOwnerType.ORGANIZATION, "personal-fallback");
        teamPrimary = bundle(ModelConnectionOwnerType.TEAM, "team-primary");
        organizationPrimary = bundle(
                ModelConnectionOwnerType.ORGANIZATION, "organization-primary");
        configuration = configuration(
                profile,
                template,
                direct(personalPrimary.selection(), personalFallback.selection()),
                AgentExecutionModelBinding.inheritTeamDefault());
        wire(personalPrimary, personalFallback, teamPrimary, organizationPrimary);
    }

    @Test
    void resolvesPersonalDirectPrimaryAndFallbackIndependently() {
        ResolvedAgentExecutionConfiguration resolved = resolver.resolve(
                profile,
                template,
                configuration,
                new AgentExecutionScopeFacts(false, false, false, false),
                policy(),
                authorization(personalPrimary.connection().id(), personalFallback.connection().id()),
                RESOLVED_AT);

        assertEquals(AgentExecutionScope.PERSONAL, resolved.executionScope());
        assertEquals(AgentModelBindingSource.DIRECT, resolved.bindingSource());
        assertTrue(resolved.modelDefault().isEmpty());
        assertEquals(personalPrimary.connection().id(), resolved.primary().connectionId());
        assertEquals(
                personalFallback.connection().id(),
                resolved.fallback().orElseThrow().connectionId());
    }

    @Test
    void teamDefaultWinsBeforeOrganizationDefaultAndIsFixedByRevision() {
        AgentModelDefault teamDefault = modelDefault(
                AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID), teamPrimary);
        AgentModelDefault organizationDefault = modelDefault(
                AgentModelDefaultScope.organization(ORGANIZATION_ID), organizationPrimary);
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of(teamDefault));
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.organization(ORGANIZATION_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of(organizationDefault));

        ResolvedAgentExecutionConfiguration resolved = resolver.resolve(
                profile,
                template,
                configuration,
                new AgentExecutionScopeFacts(true, false, false, false),
                policy(),
                authorization(teamPrimary.connection().id()),
                RESOLVED_AT);

        assertEquals(AgentModelBindingSource.TEAM_DEFAULT, resolved.bindingSource());
        assertEquals(teamDefault.revision(), resolved.modelDefault().orElseThrow().revision());
        assertEquals(teamPrimary.connection().id(), resolved.primary().connectionId());
        verify(defaults, never()).findCurrentCandidates(
                AgentModelDefaultScope.organization(ORGANIZATION_ID),
                template.templateVersion(),
                AgentExecutionScope.TEAM);
    }

    @Test
    void fallsBackFromAbsentTeamDefaultToExactOrganizationDefault() {
        AgentModelDefault organizationDefault = modelDefault(
                AgentModelDefaultScope.organization(ORGANIZATION_ID), organizationPrimary);
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of());
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.organization(ORGANIZATION_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of(organizationDefault));

        ResolvedAgentExecutionConfiguration resolved = resolver.resolve(
                profile,
                template,
                configuration,
                new AgentExecutionScopeFacts(false, true, false, false),
                policy(),
                authorization(organizationPrimary.connection().id()),
                RESOLVED_AT);

        assertEquals(AgentModelBindingSource.ORGANIZATION_DEFAULT, resolved.bindingSource());
        assertEquals(organizationPrimary.connection().id(), resolved.primary().connectionId());
    }

    @Test
    void failsClosedForMissingOrAmbiguousDefaults() {
        AgentModelDefault teamDefault = modelDefault(
                AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID), teamPrimary);
        when(defaults.findCurrentCandidates(any(), any(), any()))
                .thenReturn(List.of(teamDefault, teamDefault));

        assertRejected(
                AgentModelPreflightRejectionCode.DEFAULT_AMBIGUOUS,
                () -> resolveTeam(authorization(teamPrimary.connection().id())));

        when(defaults.findCurrentCandidates(any(), any(), any())).thenReturn(List.of());
        assertRejected(
                AgentModelPreflightRejectionCode.DEFAULT_MISSING,
                () -> resolveTeam(authorization(teamPrimary.connection().id())));
    }

    @Test
    void fallbackMustPassTheSameConnectionGrantPreflightAsPrimary() {
        assertRejected(
                AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN,
                () -> resolver.resolve(
                        profile,
                        template,
                        configuration,
                        new AgentExecutionScopeFacts(false, false, false, false),
                        policy(),
                        authorization(personalPrimary.connection().id()),
                        RESOLVED_AT));
    }

    @Test
    void unavailableSelectedDefaultDoesNotFallThroughToOrganization() {
        AgentModelDefault teamDefault = modelDefault(
                AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID), teamPrimary);
        AgentModelDefault organizationDefault = modelDefault(
                AgentModelDefaultScope.organization(ORGANIZATION_ID), organizationPrimary);
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of(teamDefault));
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.organization(ORGANIZATION_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of(organizationDefault));
        when(connections.findById(ORGANIZATION_ID, teamPrimary.connection().id()))
                .thenReturn(Optional.empty());

        assertRejected(
                AgentModelPreflightRejectionCode.CONNECTION_UNAVAILABLE,
                () -> resolveTeam(authorization(teamPrimary.connection().id())));
        verify(defaults, never()).findCurrentCandidates(
                AgentModelDefaultScope.organization(ORGANIZATION_ID),
                template.templateVersion(),
                AgentExecutionScope.TEAM);
    }

    @Test
    void teamExecutionRequiresCurrentParticipationAndEffectivePrice() {
        AgentModelDefault teamDefault = modelDefault(
                AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID), teamPrimary);
        when(defaults.findCurrentCandidates(
                        AgentModelDefaultScope.team(ORGANIZATION_ID, TEAM_ID),
                        template.templateVersion(),
                        AgentExecutionScope.TEAM))
                .thenReturn(List.of(teamDefault));
        AgentExecutionAuthorizationFacts inactiveMember = new AgentExecutionAuthorizationFacts(
                OWNER_ID,
                true,
                false,
                true,
                true,
                true,
                Set.of(teamPrimary.connection().id()));
        assertRejected(
                AgentModelPreflightRejectionCode.TEAM_PARTICIPATION_REQUIRED,
                () -> resolveTeam(inactiveMember));

        when(prices.findEffectivePrice(teamPrimary.catalog().coordinate(), RESOLVED_AT))
                .thenReturn(Optional.empty());
        assertRejected(
                AgentModelPreflightRejectionCode.PRICE_UNAVAILABLE,
                () -> resolveTeam(authorization(teamPrimary.connection().id())));
    }

    @Test
    void rejectsCapabilityRegionAndBudgetBeforeAnyAgentScopeConstruction() {
        AgentModelPolicyConstraints unsupportedCapability = new AgentModelPolicyConstraints(
                Set.of(new ModelCapability("vision")),
                Set.of(GLOBAL),
                Set.of(ModelDataRetentionMode.NONE),
                Optional.of(Duration.ofDays(1)),
                false,
                16_000,
                2_048);
        assertRejected(
                AgentModelPreflightRejectionCode.CAPABILITY_UNSUPPORTED,
                () -> resolver.resolve(
                        profile,
                        template,
                        configuration,
                        new AgentExecutionScopeFacts(false, false, false, false),
                        unsupportedCapability,
                        authorization(
                                personalPrimary.connection().id(),
                                personalFallback.connection().id()),
                        RESOLVED_AT));

        AgentModelPolicyConstraints forbiddenRegion = new AgentModelPolicyConstraints(
                Set.of(),
                Set.of(new ModelRegion("eu")),
                Set.of(ModelDataRetentionMode.NONE),
                Optional.of(Duration.ofDays(1)),
                false,
                16_000,
                2_048);
        assertRejected(
                AgentModelPreflightRejectionCode.REGION_FORBIDDEN,
                () -> resolver.resolve(
                        profile,
                        template,
                        configuration,
                        new AgentExecutionScopeFacts(false, false, false, false),
                        forbiddenRegion,
                        authorization(
                                personalPrimary.connection().id(),
                                personalFallback.connection().id()),
                        RESOLVED_AT));

        AgentExecutionAuthorizationFacts exhaustedBudget =
                new AgentExecutionAuthorizationFacts(
                        OWNER_ID,
                        true,
                        true,
                        true,
                        false,
                        true,
                        Set.of(
                                personalPrimary.connection().id(),
                                personalFallback.connection().id()));
        assertRejected(
                AgentModelPreflightRejectionCode.BUDGET_EXHAUSTED,
                () -> resolver.resolve(
                        profile,
                        template,
                        configuration,
                        new AgentExecutionScopeFacts(false, false, false, false),
                        policy(),
                        exhaustedBudget,
                        RESOLVED_AT));
    }

    private ResolvedAgentExecutionConfiguration resolveTeam(
            AgentExecutionAuthorizationFacts authorization) {
        return resolver.resolve(
                profile,
                template,
                configuration,
                new AgentExecutionScopeFacts(true, false, false, false),
                policy(),
                authorization,
                RESOLVED_AT);
    }

    private AgentModelDefault modelDefault(
            AgentModelDefaultScope scope, ModelBundle model) {
        return AgentModelDefault.publishInitial(
                template,
                scope,
                AgentExecutionScope.TEAM,
                direct(model.selection(), null),
                configuration.policyPack(),
                ACTOR,
                CREATED_AT);
    }

    private void wire(ModelBundle... bundles) {
        for (ModelBundle bundle : bundles) {
            when(providers.findByKey(bundle.provider().providerKey()))
                    .thenReturn(Optional.of(bundle.provider()));
            when(connections.findById(ORGANIZATION_ID, bundle.connection().id()))
                    .thenReturn(Optional.of(bundle.connection()));
            when(catalogs.findByCoordinate(bundle.catalog().coordinate()))
                    .thenReturn(Optional.of(bundle.catalog()));
            when(prices.findEffectivePrice(bundle.catalog().coordinate(), RESOLVED_AT))
                    .thenReturn(Optional.of(bundle.price()));
        }
    }

    private static AgentExecutionAuthorizationFacts authorization(
            ModelConnectionId... usableConnections) {
        return new AgentExecutionAuthorizationFacts(
                OWNER_ID,
                true,
                true,
                true,
                true,
                true,
                Set.of(usableConnections));
    }

    private static AgentModelPolicyConstraints policy() {
        return new AgentModelPolicyConstraints(
                Set.of(),
                Set.of(GLOBAL),
                Set.of(ModelDataRetentionMode.NONE),
                Optional.of(Duration.ofDays(1)),
                false,
                16_000,
                2_048);
    }

    private static AgentTemplateDefinition template() {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(ORGANIZATION_ID),
                new AgentTemplateKey("coding"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("source-code.change")),
                        Set.of(new AgentTemplateCapability("model.tool-calling"))),
                AgentTemplatePolicy.define(
                        "Perform the approved coding task.",
                        Set.of(new AgentToolKey("repository.read")),
                        Set.of("secure-coding"),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING),
                        Set.of()),
                ACTOR,
                CREATED_AT);
    }

    private static AgentProfile profile(AgentTemplateDefinition template) {
        return AgentProfile.reconstituteTemplateInstance(
                AgentProfileId.generate(),
                WorkspaceScope.team(ORGANIZATION_ID, TEAM_ID),
                WorkspaceId.generate(),
                PrincipalId.generate(),
                AgentOwnership.user(ORGANIZATION_ID, TEAM_ID, TeamMemberId.generate()),
                AgentRuntimeRole.SPECIALIST,
                template.templateVersion(),
                AgentProfileType.SPECIALIST,
                false,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(ACTOR, CREATED_AT));
    }

    private static AgentConfigurationVersion configuration(
            AgentProfile profile,
            AgentTemplateDefinition template,
            AgentDirectModelBinding personal,
            AgentExecutionModelBinding team) {
        return AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.of(OWNER_ID),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL, personal)),
                Optional.of(team),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                new PolicyPackReference(PolicyPackId.generate(), 1),
                SafeModelGenerateOptions.defaults(),
                ACTOR,
                CREATED_AT);
    }

    private static AgentDirectModelBinding direct(
            AgentModelSelection primary, AgentModelSelection fallback) {
        return new AgentDirectModelBinding(primary, Optional.ofNullable(fallback));
    }

    private static ModelBundle bundle(ModelConnectionOwnerType ownerType, String suffix) {
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("provider-" + suffix),
                "Provider " + suffix,
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.example.com/" + suffix),
                Set.of(GLOBAL),
                ModelDataPolicy.noRetention(),
                ACTOR,
                CREATED_AT);
        ModelCatalogEntry catalog = ModelCatalogEntry.publishInitial(
                provider,
                ModelCatalogEntryId.generate(),
                new ModelId("model-" + suffix),
                new ModelRevision("revision-" + suffix),
                "Model " + suffix,
                128_000,
                8_192,
                Set.of(new ModelCapability("tool-calling")),
                Set.of(GLOBAL),
                ACTOR,
                CREATED_AT);
        ModelConnectionOwner owner = switch (ownerType) {
            case USER -> new ModelConnectionOwner(
                    ORGANIZATION_ID,
                    ModelConnectionOwnerType.USER,
                    OWNER_ID.value(),
                    Optional.empty(),
                    Optional.of(OWNER_ID));
            case TEAM -> new ModelConnectionOwner(
                    ORGANIZATION_ID,
                    ModelConnectionOwnerType.TEAM,
                    TEAM_ID.value(),
                    Optional.of(TEAM_ID),
                    Optional.empty());
            case ORGANIZATION -> ModelConnectionOwner.organization(ORGANIZATION_ID);
        };
        ModelCredentialSubject credentialSubject = switch (ownerType) {
            case USER -> ModelCredentialSubject.principal(ORGANIZATION_ID, OWNER_ID);
            case TEAM -> ModelCredentialSubject.team(ORGANIZATION_ID, TEAM_ID);
            case ORGANIZATION -> ModelCredentialSubject.organization(ORGANIZATION_ID);
        };
        ModelBillingSubject billingSubject = switch (ownerType) {
            case USER -> ModelBillingSubject.principal(ORGANIZATION_ID, OWNER_ID);
            case TEAM -> ModelBillingSubject.team(ORGANIZATION_ID, TEAM_ID);
            case ORGANIZATION -> ModelBillingSubject.organization(ORGANIZATION_ID);
        };
        ModelConnection connection = ModelConnection.open(
                        provider,
                        ModelConnectionId.generate(),
                        owner,
                        new ModelEndpoint("https://gateway.example.com/" + suffix),
                        GLOBAL,
                        new ModelCredentialBinding(
                                CredentialId.generate(),
                                credentialSubject,
                                new ModelCredentialVersion(0)),
                        billingSubject,
                        ACTOR,
                        CREATED_AT)
                .recordVerificationSuccess(
                        provider,
                        0,
                        new ModelCredentialVersion(0),
                        ACTOR,
                        VERIFIED_AT);
        ModelPriceRevision price = ModelPriceRevision.publish(
                catalog.coordinate(),
                1,
                CREATED_AT,
                new ModelTokenPrice(
                        new BigDecimal("0.1"),
                        new BigDecimal("0.3"),
                        Optional.empty(),
                        "USD"),
                new ModelPriceSource("fixture"),
                ACTOR,
                CREATED_AT);
        return new ModelBundle(
                provider,
                connection,
                catalog,
                price,
                AgentModelSelection.capture(connection, catalog));
    }

    private static void assertRejected(
            AgentModelPreflightRejectionCode expected,
            org.junit.jupiter.api.function.Executable call) {
        AgentModelPreflightException failure = assertThrows(
                AgentModelPreflightException.class, call);
        assertEquals(expected, failure.reason());
    }

    private record ModelBundle(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog,
            ModelPriceRevision price,
            AgentModelSelection selection) {}
}
