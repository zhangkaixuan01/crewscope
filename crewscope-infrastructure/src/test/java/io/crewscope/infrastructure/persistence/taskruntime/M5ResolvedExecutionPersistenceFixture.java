package io.crewscope.infrastructure.persistence.taskruntime;

import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelBindingSource;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
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
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.agent.ResolvedModelSelection;
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
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Builds one exact non-secret execution coordinate for PolicySnapshot v2 database tests. */
final class M5ResolvedExecutionPersistenceFixture {

    private M5ResolvedExecutionPersistenceFixture() {}

    static ResolvedAgentExecutionConfiguration create(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            AgentProfileId profileId,
            PrincipalId agentPrincipalId,
            PrincipalId actor,
            PolicyPackReference policyPack,
            UtcTimestamp createdAt) {
        ModelRegion region = new ModelRegion("global");
        AgentTemplateDefinition template = AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey("team-coordinator"),
                AgentRuntimeRole.TEAM_COORDINATOR,
                Set.of(AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("task.orchestration")),
                        Set.of(new AgentTemplateCapability("model.tool-calling"))),
                AgentTemplatePolicy.define(
                        "Coordinate the approved team task.",
                        Set.of(new AgentToolKey("repository.read")),
                        Set.of("team-coordination"),
                        Optional.empty(),
                        Set.of(),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                actor,
                createdAt);
        AgentProfile profile = AgentProfile.reconstituteTemplateInstance(
                profileId,
                WorkspaceScope.team(organizationId, teamId),
                workspaceId,
                agentPrincipalId,
                AgentOwnership.team(organizationId, teamId),
                AgentRuntimeRole.TEAM_COORDINATOR,
                template.templateVersion(),
                AgentProfileType.TEAM,
                false,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(actor, createdAt));
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("snapshot-provider"),
                "Snapshot Provider",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.example.com/snapshot"),
                Set.of(region),
                ModelDataPolicy.noRetention(),
                actor,
                createdAt);
        ModelCatalogEntry catalog = ModelCatalogEntry.publishInitial(
                provider,
                ModelCatalogEntryId.generate(),
                new ModelId("snapshot-model"),
                new ModelRevision("2026-08-23"),
                "Snapshot Model",
                128_000,
                8_192,
                Set.of(new ModelCapability("tool-calling")),
                Set.of(region),
                actor,
                createdAt);
        ModelConnectionOwner owner = new ModelConnectionOwner(
                organizationId,
                ModelConnectionOwnerType.TEAM,
                teamId.value(),
                Optional.of(teamId),
                Optional.empty());
        ModelConnection connection = ModelConnection.open(
                        provider,
                        ModelConnectionId.generate(),
                        owner,
                        new ModelEndpoint("https://gateway.example.com/snapshot"),
                        region,
                        new ModelCredentialBinding(
                                CredentialId.generate(),
                                ModelCredentialSubject.team(organizationId, teamId),
                                new ModelCredentialVersion(0)),
                        ModelBillingSubject.team(organizationId, teamId),
                        actor,
                        createdAt)
                .recordVerificationSuccess(
                        provider,
                        0,
                        new ModelCredentialVersion(0),
                        actor,
                        UtcTimestamp.parse("2026-08-23T08:01:00Z"));
        ModelPriceRevision price = ModelPriceRevision.publish(
                catalog.coordinate(),
                1,
                createdAt,
                new ModelTokenPrice(
                        new BigDecimal("0.1"),
                        new BigDecimal("0.2"),
                        Optional.empty(),
                        "USD"),
                new ModelPriceSource("snapshot-fixture"),
                actor,
                createdAt);
        AgentModelSelection selection = AgentModelSelection.capture(connection, catalog);
        AgentConfigurationVersion configuration = AgentConfigurationVersion.createInitial(
                profile,
                template,
                Optional.empty(),
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.direct(
                        AgentExecutionScope.TEAM,
                        new AgentDirectModelBinding(selection, Optional.empty()))),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                policyPack,
                SafeModelGenerateOptions.defaults(),
                actor,
                createdAt);
        ResolvedModelSelection resolvedModel = ResolvedModelSelection.resolve(
                ResolvedModelRole.PRIMARY,
                selection,
                provider,
                connection,
                catalog,
                price,
                profile.ownership(),
                Optional.empty(),
                AgentExecutionScope.TEAM,
                new AgentModelPolicyConstraints(
                        Set.of(new ModelCapability("tool-calling")),
                        Set.of(region),
                        Set.of(ModelDataRetentionMode.NONE),
                        Optional.of(Duration.ofDays(1)),
                        false,
                        32_000,
                        4_096),
                new AgentExecutionAuthorizationFacts(
                        actor, true, true, true, true, true, Set.of(connection.id())));
        return ResolvedAgentExecutionConfiguration.resolve(
                profile,
                template,
                configuration,
                AgentExecutionScope.TEAM,
                AgentModelBindingSource.DIRECT,
                Optional.empty(),
                resolvedModel,
                Optional.empty());
    }
}
