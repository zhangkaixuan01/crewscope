package io.crewscope.domain.agent;

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
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRevision;
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
import java.util.Optional;
import java.util.Set;

/** Deterministic non-secret fixtures shared by M5-D04 domain contract tests. */
final class AgentConfigurationTestFixture {

    static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    static final TeamId TEAM_ID = TeamId.generate();
    static final TeamId OTHER_TEAM_ID = TeamId.generate();
    static final PrincipalId OWNER_USER_ID = PrincipalId.generate();
    static final PrincipalId OTHER_USER_ID = PrincipalId.generate();
    static final PrincipalId ACTOR = PrincipalId.generate();
    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-23T02:00:00Z");
    static final UtcTimestamp VERIFIED_AT = UtcTimestamp.parse("2026-08-23T02:01:00Z");

    private AgentConfigurationTestFixture() {}

    static AgentTemplateDefinition specialistTemplate() {
        return template(
                "coding",
                AgentRuntimeRole.SPECIALIST,
                Set.of(
                        AgentOwnershipType.USER,
                        AgentOwnershipType.TEAM,
                        AgentOwnershipType.ORGANIZATION),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM));
    }

    static AgentTemplateDefinition personalTemplate() {
        return template(
                "personal-assistant",
                AgentRuntimeRole.PERSONAL_ASSISTANT,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM));
    }

    static AgentTemplateDefinition template(
            String key,
            AgentRuntimeRole runtimeRole,
            Set<AgentOwnershipType> ownershipTypes,
            Set<AgentExecutionScope> executionScopes) {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(ORGANIZATION_ID),
                new AgentTemplateKey(key),
                runtimeRole,
                ownershipTypes,
                executionScopes,
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("source-code.change")),
                        Set.of(new AgentTemplateCapability("model.tool-calling"))),
                AgentTemplatePolicy.define(
                        "Perform the approved task within the supplied evidence and policy.",
                        Set.of(new AgentToolKey("repository.read")),
                        Set.of("java-review", "secure-coding"),
                        Optional.of("{\"type\":\"object\"}"),
                        Set.of(
                                AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                                AgentConfigurableSlot.APPROVED_SKILLS,
                                AgentConfigurableSlot.KNOWLEDGE_SCOPE,
                                AgentConfigurableSlot.MODEL_BINDING,
                                AgentConfigurableSlot.BUDGET,
                                AgentConfigurableSlot.OUTPUT_PREFERENCE),
                        Set.of(AgentConfigurableSlot.PROVIDER_BINDING)),
                ACTOR,
                CREATED_AT);
    }

    static AgentProfile userProfile(AgentTemplateDefinition template) {
        return profile(
                AgentOwnership.user(ORGANIZATION_ID, TEAM_ID, TeamMemberId.generate()),
                template.runtimeRole(),
                template,
                template.runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT);
    }

    static AgentProfile teamProfile(AgentTemplateDefinition template) {
        return profile(
                AgentOwnership.team(ORGANIZATION_ID, TEAM_ID),
                template.runtimeRole(),
                template,
                false);
    }

    static AgentProfile organizationProfile(AgentTemplateDefinition template) {
        return profile(
                AgentOwnership.organization(ORGANIZATION_ID),
                template.runtimeRole(),
                template,
                false);
    }

    private static AgentProfile profile(
            AgentOwnership ownership,
            AgentRuntimeRole runtimeRole,
            AgentTemplateDefinition template,
            boolean defaultProfile) {
        WorkspaceScope scope = ownership.teamId()
                .map(teamId -> WorkspaceScope.team(ORGANIZATION_ID, teamId))
                .orElseGet(() -> WorkspaceScope.personal(ORGANIZATION_ID));
        AgentProfileType type = switch (runtimeRole) {
            case PERSONAL_ASSISTANT -> AgentProfileType.PERSONAL;
            case TEAM_COORDINATOR -> AgentProfileType.TEAM;
            case SPECIALIST -> AgentProfileType.SPECIALIST;
        };
        return AgentProfile.reconstituteTemplateInstance(
                AgentProfileId.generate(),
                scope,
                WorkspaceId.generate(),
                PrincipalId.generate(),
                ownership,
                runtimeRole,
                template.templateVersion(),
                type,
                defaultProfile,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(ACTOR, CREATED_AT));
    }

    static AgentModelSelection userSelection(PrincipalId principalId, String suffix) {
        return selection(userOwner(principalId), suffix);
    }

    static AgentModelSelection teamSelection(TeamId teamId, String suffix) {
        return selection(teamOwner(teamId), suffix);
    }

    static AgentModelSelection organizationSelection(String suffix) {
        return selection(ModelConnectionOwner.organization(ORGANIZATION_ID), suffix);
    }

    static AgentModelSelection selection(ModelConnectionOwner owner, String suffix) {
        ModelProviderDefinition provider = provider("provider-" + suffix);
        ModelCatalogEntry catalog = ModelCatalogEntry.publishInitial(
                provider,
                ModelCatalogEntryId.generate(),
                new ModelId("model-" + suffix),
                new ModelRevision("revision-" + suffix),
                "Model " + suffix,
                128_000,
                8_192,
                Set.of(new ModelCapability("tool-calling")),
                Set.of(new ModelRegion("global")),
                ACTOR,
                CREATED_AT);
        ModelCredentialSubject credentialSubject = switch (owner.type()) {
            case USER -> ModelCredentialSubject.principal(
                    ORGANIZATION_ID, owner.userPrincipalId().orElseThrow());
            case TEAM -> ModelCredentialSubject.team(
                    ORGANIZATION_ID, owner.teamId().orElseThrow());
            case ORGANIZATION -> ModelCredentialSubject.organization(ORGANIZATION_ID);
        };
        ModelBillingSubject billingSubject = switch (owner.type()) {
            case USER -> ModelBillingSubject.principal(
                    ORGANIZATION_ID, owner.userPrincipalId().orElseThrow());
            case TEAM -> ModelBillingSubject.team(
                    ORGANIZATION_ID, owner.teamId().orElseThrow());
            case ORGANIZATION -> ModelBillingSubject.organization(ORGANIZATION_ID);
        };
        ModelConnection connection = ModelConnection.open(
                        provider,
                        ModelConnectionId.generate(),
                        owner,
                        new ModelEndpoint("https://gateway.example.com/" + suffix),
                        new ModelRegion("global"),
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
        return AgentModelSelection.capture(connection, catalog);
    }

    static ModelConnectionOwner userOwner(PrincipalId principalId) {
        return new ModelConnectionOwner(
                ORGANIZATION_ID,
                ModelConnectionOwnerType.USER,
                principalId.value(),
                Optional.empty(),
                Optional.of(principalId));
    }

    static ModelConnectionOwner teamOwner(TeamId teamId) {
        return new ModelConnectionOwner(
                ORGANIZATION_ID,
                ModelConnectionOwnerType.TEAM,
                teamId.value(),
                Optional.of(teamId),
                Optional.empty());
    }

    private static ModelProviderDefinition provider(String key) {
        return ModelProviderDefinition.publish(
                new ModelProviderKey(key),
                "Provider " + key,
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.example.com/" + key),
                Set.of(new ModelRegion("global")),
                ModelDataPolicy.noRetention(),
                ACTOR,
                CREATED_AT);
    }
}
