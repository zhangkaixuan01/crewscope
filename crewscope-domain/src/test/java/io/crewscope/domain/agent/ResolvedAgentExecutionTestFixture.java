package io.crewscope.domain.agent;

import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
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
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Shared exact M5 runtime coordinate for PolicySnapshot v2 contract tests. */
public final class ResolvedAgentExecutionTestFixture {

    private ResolvedAgentExecutionTestFixture() {}

    public static ResolvedAgentExecutionConfiguration create() {
        return create(AgentConfigurationTestFixture.specialistTemplate(), Optional.empty());
    }

    /** Builds a runtime snapshot with caller-controlled Prompt field boundaries. */
    static ResolvedAgentExecutionConfiguration createWithPromptParts(
            String systemPromptBaseline, Optional<String> supplementalInstructions) {
        AgentTemplateDefinition template = AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(
                        AgentConfigurationTestFixture.ORGANIZATION_ID),
                new AgentTemplateKey("prompt-hash-specialist"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("source-code.change")),
                        Set.of(new AgentTemplateCapability("model.tool-calling"))),
                AgentTemplatePolicy.define(
                        systemPromptBaseline,
                        Set.of(new AgentToolKey("repository.read")),
                        Set.of("secure-coding"),
                        Optional.empty(),
                        Set.of(
                                AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                                AgentConfigurableSlot.MODEL_BINDING),
                        Set.of()),
                AgentConfigurationTestFixture.ACTOR,
                AgentConfigurationTestFixture.CREATED_AT);
        return create(template, supplementalInstructions);
    }

    private static ResolvedAgentExecutionConfiguration create(
            AgentTemplateDefinition template, Optional<String> supplementalInstructions) {
        AgentProfile profile = AgentConfigurationTestFixture.userProfile(template);
        ModelRegion region = new ModelRegion("global");
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("snapshot-provider"),
                "Snapshot Provider",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.example.com/snapshot"),
                Set.of(region),
                ModelDataPolicy.noRetention(),
                AgentConfigurationTestFixture.ACTOR,
                AgentConfigurationTestFixture.CREATED_AT);
        ModelCatalogEntry catalog = ModelCatalogEntry.publishInitial(
                provider,
                ModelCatalogEntryId.generate(),
                new ModelId("snapshot-model"),
                new ModelRevision("snapshot-revision"),
                "Snapshot Model",
                128_000,
                8_192,
                Set.of(new ModelCapability("tool-calling")),
                Set.of(region),
                AgentConfigurationTestFixture.ACTOR,
                AgentConfigurationTestFixture.CREATED_AT);
        ModelConnection connection = ModelConnection.open(
                        provider,
                        ModelConnectionId.generate(),
                        AgentConfigurationTestFixture.userOwner(
                                AgentConfigurationTestFixture.OWNER_USER_ID),
                        new ModelEndpoint("https://gateway.example.com/snapshot"),
                        region,
                        new ModelCredentialBinding(
                                CredentialId.generate(),
                                ModelCredentialSubject.principal(
                                        AgentConfigurationTestFixture.ORGANIZATION_ID,
                                        AgentConfigurationTestFixture.OWNER_USER_ID),
                                new ModelCredentialVersion(0)),
                        ModelBillingSubject.principal(
                                AgentConfigurationTestFixture.ORGANIZATION_ID,
                                AgentConfigurationTestFixture.OWNER_USER_ID),
                        AgentConfigurationTestFixture.ACTOR,
                        AgentConfigurationTestFixture.CREATED_AT)
                .recordVerificationSuccess(
                        provider,
                        0,
                        new ModelCredentialVersion(0),
                        AgentConfigurationTestFixture.ACTOR,
                        AgentConfigurationTestFixture.VERIFIED_AT);
        ModelPriceRevision price = ModelPriceRevision.publish(
                catalog.coordinate(),
                1,
                AgentConfigurationTestFixture.CREATED_AT,
                new ModelTokenPrice(
                        new BigDecimal("0.1"),
                        new BigDecimal("0.2"),
                        Optional.empty(),
                        "USD"),
                new ModelPriceSource("snapshot-fixture"),
                AgentConfigurationTestFixture.ACTOR,
                AgentConfigurationTestFixture.CREATED_AT);
        AgentModelSelection selection = AgentModelSelection.capture(connection, catalog);
        PolicyPackReference policyPack = new PolicyPackReference(PolicyPackId.generate(), 1);
        AgentConfigurationVersion configuration = AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.of(AgentConfigurationTestFixture.OWNER_USER_ID),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.PERSONAL,
                        new AgentDirectModelBinding(selection, Optional.empty()))),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                supplementalInstructions,
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                policyPack,
                SafeModelGenerateOptions.defaults(),
                AgentConfigurationTestFixture.ACTOR,
                AgentConfigurationTestFixture.CREATED_AT);
        ResolvedModelSelection resolvedModel = ResolvedModelSelection.resolve(
                ResolvedModelRole.PRIMARY,
                selection,
                provider,
                connection,
                catalog,
                price,
                configuration.ownership(),
                configuration.ownerUserPrincipalId(),
                AgentExecutionScope.PERSONAL,
                new AgentModelPolicyConstraints(
                        Set.of(new ModelCapability("tool-calling")),
                        Set.of(region),
                        Set.of(ModelDataRetentionMode.NONE),
                        Optional.of(Duration.ofDays(1)),
                        false,
                        32_000,
                        4_096),
                new AgentExecutionAuthorizationFacts(
                        AgentConfigurationTestFixture.OWNER_USER_ID,
                        true,
                        true,
                        true,
                        true,
                        true,
                        Set.of(connection.id())));
        return ResolvedAgentExecutionConfiguration.resolve(
                profile,
                template,
                configuration,
                AgentExecutionScope.PERSONAL,
                AgentModelBindingSource.DIRECT,
                Optional.empty(),
                resolvedModel,
                Optional.empty());
    }
}
