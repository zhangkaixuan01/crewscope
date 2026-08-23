package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import io.crewscope.domain.model.ModelTrainingUsagePolicy;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResolvedModelSelectionTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final PrincipalId OWNER_ID = PrincipalId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-23T03:00:00Z");
    private static final UtcTimestamp VERIFIED_AT =
            UtcTimestamp.parse("2026-08-23T03:01:00Z");
    private static final ModelRegion GLOBAL = new ModelRegion("global");

    @Test
    void fixesProviderAdapterConnectionCredentialCatalogModelAndPrice() {
        Fixture fixture = fixture(ModelDataPolicy.noRetention());

        ResolvedModelSelection resolved = resolve(
                fixture, constraints(), authorization(fixture.connection().id()));

        assertEquals(new ModelAdapterKey("openai-compatible"), resolved.adapterKey());
        assertEquals(fixture.connection().version(), resolved.connectionVersion());
        assertEquals(
                fixture.connection().credentialBinding().credentialVersion(),
                resolved.credentialVersion());
        assertEquals(fixture.catalog().modelRevision(), resolved.modelRevision());
        assertEquals(fixture.price().revision(), resolved.priceRevision());
        assertEquals(fixture.price().tokenPrice(), resolved.tokenPrice());
        assertEquals(64, resolved.resolutionHash().value().length());
    }

    @Test
    void priceOrCredentialCoordinatesChangeTheResolvedHash() {
        Fixture fixture = fixture(ModelDataPolicy.noRetention());
        ResolvedModelSelection original = resolve(
                fixture, constraints(), authorization(fixture.connection().id()));
        ModelPriceRevision nextPrice = ModelPriceRevision.publish(
                fixture.catalog().coordinate(),
                2,
                UtcTimestamp.parse("2026-08-23T03:02:00Z"),
                new ModelTokenPrice(
                        new BigDecimal("0.2"),
                        new BigDecimal("0.4"),
                        Optional.empty(),
                        "USD"),
                new ModelPriceSource("next"),
                ACTOR,
                UtcTimestamp.parse("2026-08-23T03:02:00Z"));

        ResolvedModelSelection repriced = ResolvedModelSelection.resolve(
                ResolvedModelRole.PRIMARY,
                fixture.selection(),
                fixture.provider(),
                fixture.connection(),
                fixture.catalog(),
                nextPrice,
                ownership(),
                Optional.of(OWNER_ID),
                AgentExecutionScope.PERSONAL,
                constraints(),
                authorization(fixture.connection().id()));

        assertNotEquals(original.resolutionHash(), repriced.resolutionHash());
    }

    @Test
    void enforcesCapabilityRegionRetentionTrainingAndTokenIntersection() {
        Fixture fixture = fixture(new ModelDataPolicy(
                ModelDataRetentionMode.TIME_BOUND,
                Optional.of(Duration.ofDays(30)),
                ModelTrainingUsagePolicy.PROHIBITED));

        assertRejected(
                AgentModelPreflightRejectionCode.CAPABILITY_UNSUPPORTED,
                () -> resolve(
                        fixture,
                        new AgentModelPolicyConstraints(
                                Set.of(new ModelCapability("vision")),
                                Set.of(GLOBAL),
                                Set.of(ModelDataRetentionMode.TIME_BOUND),
                                Optional.of(Duration.ofDays(30)),
                                false,
                                32_000,
                                4_096),
                        authorization(fixture.connection().id())));
        assertRejected(
                AgentModelPreflightRejectionCode.REGION_FORBIDDEN,
                () -> resolve(
                        fixture,
                        new AgentModelPolicyConstraints(
                                Set.of(new ModelCapability("tool-calling")),
                                Set.of(new ModelRegion("cn")),
                                Set.of(ModelDataRetentionMode.TIME_BOUND),
                                Optional.of(Duration.ofDays(30)),
                                false,
                                32_000,
                                4_096),
                        authorization(fixture.connection().id())));
        assertRejected(
                AgentModelPreflightRejectionCode.DATA_POLICY_FORBIDDEN,
                () -> resolve(
                        fixture,
                        new AgentModelPolicyConstraints(
                                Set.of(new ModelCapability("tool-calling")),
                                Set.of(GLOBAL),
                                Set.of(ModelDataRetentionMode.TIME_BOUND),
                                Optional.of(Duration.ofDays(7)),
                                false,
                                32_000,
                                4_096),
                        authorization(fixture.connection().id())));
        assertRejected(
                AgentModelPreflightRejectionCode.CONTEXT_LIMIT_EXCEEDED,
                () -> resolve(
                        fixture,
                        new AgentModelPolicyConstraints(
                                Set.of(new ModelCapability("tool-calling")),
                                Set.of(GLOBAL),
                                Set.of(ModelDataRetentionMode.TIME_BOUND),
                                Optional.of(Duration.ofDays(30)),
                                false,
                                256_000,
                                4_096),
                        authorization(fixture.connection().id())));
    }

    @Test
    void enforcesPrincipalResponsibilityBudgetQuotaAndConnectionGrant() {
        Fixture fixture = fixture(ModelDataPolicy.noRetention());

        assertRejected(
                AgentModelPreflightRejectionCode.PRINCIPAL_INACTIVE,
                () -> resolve(
                        fixture,
                        constraints(),
                        new AgentExecutionAuthorizationFacts(
                                OWNER_ID, false, true, true, true, true,
                                Set.of(fixture.connection().id()))));
        assertRejected(
                AgentModelPreflightRejectionCode.RESPONSIBILITY_REQUIRED,
                () -> resolve(
                        fixture,
                        constraints(),
                        new AgentExecutionAuthorizationFacts(
                                OWNER_ID, true, true, false, true, true,
                                Set.of(fixture.connection().id()))));
        assertRejected(
                AgentModelPreflightRejectionCode.BUDGET_EXHAUSTED,
                () -> resolve(
                        fixture,
                        constraints(),
                        new AgentExecutionAuthorizationFacts(
                                OWNER_ID, true, true, true, false, true,
                                Set.of(fixture.connection().id()))));
        assertRejected(
                AgentModelPreflightRejectionCode.QUOTA_EXHAUSTED,
                () -> resolve(
                        fixture,
                        constraints(),
                        new AgentExecutionAuthorizationFacts(
                                OWNER_ID, true, true, true, true, false,
                                Set.of(fixture.connection().id()))));
        assertRejected(
                AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN,
                () -> resolve(fixture, constraints(), authorization(ModelConnectionId.generate())));
    }

    @Test
    void forbidsUserConnectionForTeamExecutionAndForeignRequestingPrincipal() {
        Fixture fixture = fixture(ModelDataPolicy.noRetention());

        assertRejected(
                AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN,
                () -> ResolvedModelSelection.resolve(
                        ResolvedModelRole.PRIMARY,
                        fixture.selection(),
                        fixture.provider(),
                        fixture.connection(),
                        fixture.catalog(),
                        fixture.price(),
                        ownership(),
                        Optional.of(OWNER_ID),
                        AgentExecutionScope.TEAM,
                        constraints(),
                        authorization(fixture.connection().id())));
        assertRejected(
                AgentModelPreflightRejectionCode.CONNECTION_FORBIDDEN,
                () -> resolve(
                        fixture,
                        constraints(),
                        new AgentExecutionAuthorizationFacts(
                                PrincipalId.generate(),
                                true,
                                true,
                                true,
                                true,
                                true,
                                Set.of(fixture.connection().id()))));
    }

    @Test
    void reportsStableUnavailableCodesForProviderCatalogAndConnectionLifecycle() {
        Fixture fixture = fixture(ModelDataPolicy.noRetention());
        ModelProviderDefinition disabledProvider = fixture.provider().disable(
                ACTOR, UtcTimestamp.parse("2026-08-23T03:03:00Z"));
        ModelCatalogEntry disabledCatalog = fixture.catalog().disable(
                ACTOR, UtcTimestamp.parse("2026-08-23T03:03:00Z"));
        ModelConnection suspendedConnection = fixture.connection().suspend(
                fixture.connection().version(),
                ACTOR,
                UtcTimestamp.parse("2026-08-23T03:03:00Z"));

        assertLifecycleRejected(
                AgentModelPreflightRejectionCode.PROVIDER_UNAVAILABLE,
                fixture,
                disabledProvider,
                fixture.connection(),
                fixture.catalog());
        assertLifecycleRejected(
                AgentModelPreflightRejectionCode.CATALOG_UNAVAILABLE,
                fixture,
                fixture.provider(),
                fixture.connection(),
                disabledCatalog);
        assertLifecycleRejected(
                AgentModelPreflightRejectionCode.CONNECTION_UNAVAILABLE,
                fixture,
                fixture.provider(),
                suspendedConnection,
                fixture.catalog());
    }

    private static void assertLifecycleRejected(
            AgentModelPreflightRejectionCode expected,
            Fixture fixture,
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog) {
        assertRejected(
                expected,
                () -> ResolvedModelSelection.resolve(
                        ResolvedModelRole.PRIMARY,
                        fixture.selection(),
                        provider,
                        connection,
                        catalog,
                        fixture.price(),
                        ownership(),
                        Optional.of(OWNER_ID),
                        AgentExecutionScope.PERSONAL,
                        constraints(),
                        authorization(fixture.connection().id())));
    }

    private static ResolvedModelSelection resolve(
            Fixture fixture,
            AgentModelPolicyConstraints policy,
            AgentExecutionAuthorizationFacts authorization) {
        return ResolvedModelSelection.resolve(
                ResolvedModelRole.PRIMARY,
                fixture.selection(),
                fixture.provider(),
                fixture.connection(),
                fixture.catalog(),
                fixture.price(),
                ownership(),
                Optional.of(OWNER_ID),
                AgentExecutionScope.PERSONAL,
                policy,
                authorization);
    }

    private static AgentModelPolicyConstraints constraints() {
        return new AgentModelPolicyConstraints(
                Set.of(new ModelCapability("tool-calling")),
                Set.of(GLOBAL),
                Set.of(ModelDataRetentionMode.NONE, ModelDataRetentionMode.TIME_BOUND),
                Optional.of(Duration.ofDays(30)),
                false,
                32_000,
                4_096);
    }

    private static AgentExecutionAuthorizationFacts authorization(
            ModelConnectionId usableConnectionId) {
        return new AgentExecutionAuthorizationFacts(
                OWNER_ID,
                true,
                true,
                true,
                true,
                true,
                Set.of(usableConnectionId));
    }

    private static AgentOwnership ownership() {
        return AgentOwnership.user(ORGANIZATION_ID, TEAM_ID, TeamMemberId.generate());
    }

    private static Fixture fixture(ModelDataPolicy dataPolicy) {
        ModelProviderDefinition provider = ModelProviderDefinition.publish(
                new ModelProviderKey("deepseek"),
                "DeepSeek",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.deepseek.com/v1"),
                Set.of(GLOBAL),
                dataPolicy,
                ACTOR,
                CREATED_AT);
        ModelCatalogEntry catalog = ModelCatalogEntry.publishInitial(
                provider,
                ModelCatalogEntryId.generate(),
                new ModelId("deepseek-v4-flash"),
                new ModelRevision("2026-08"),
                "DeepSeek V4 Flash",
                128_000,
                8_192,
                Set.of(new ModelCapability("tool-calling")),
                Set.of(GLOBAL),
                ACTOR,
                CREATED_AT);
        ModelConnectionOwner owner = new ModelConnectionOwner(
                ORGANIZATION_ID,
                ModelConnectionOwnerType.USER,
                OWNER_ID.value(),
                Optional.empty(),
                Optional.of(OWNER_ID));
        ModelConnection connection = ModelConnection.open(
                        provider,
                        ModelConnectionId.generate(),
                        owner,
                        new ModelEndpoint("https://gateway.example.com/v1"),
                        GLOBAL,
                        new ModelCredentialBinding(
                                CredentialId.generate(),
                                ModelCredentialSubject.principal(ORGANIZATION_ID, OWNER_ID),
                                new ModelCredentialVersion(0)),
                        ModelBillingSubject.principal(ORGANIZATION_ID, OWNER_ID),
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
                        Optional.of(new BigDecimal("0.05")),
                        "USD"),
                new ModelPriceSource("catalog"),
                ACTOR,
                CREATED_AT);
        return new Fixture(
                provider,
                connection,
                catalog,
                price,
                AgentModelSelection.capture(connection, catalog));
    }

    private static void assertRejected(
            AgentModelPreflightRejectionCode expected, org.junit.jupiter.api.function.Executable call) {
        AgentModelPreflightException failure = assertThrows(
                AgentModelPreflightException.class, call);
        assertEquals(expected, failure.reason());
    }

    private record Fixture(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ModelCatalogEntry catalog,
            ModelPriceRevision price,
            AgentModelSelection selection) {}
}
