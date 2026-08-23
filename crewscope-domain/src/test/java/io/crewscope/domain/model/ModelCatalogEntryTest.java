package io.crewscope.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelCatalogEntryTest {

    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-23T00:10:00Z");
    private static final ModelRegion GLOBAL = new ModelRegion("global");
    private static final ModelRegion CN = new ModelRegion("cn");
    private static final ModelCapability TEXT = new ModelCapability("text.generation");
    private static final ModelCapability TOOL = new ModelCapability("tool-calling");

    @Test
    void appendsCatalogRevisionWhileKeepingStableIdentityAndExactProviderRevision() {
        ModelProviderDefinition provider = provider();
        ModelCatalogEntry revisionOne = catalog(provider);

        ModelCatalogEntry revisionTwo = revisionOne.publishNext(
                provider,
                new ModelRevision("DeepSeek-V4-Flash-0901"),
                "DeepSeek V4 Flash September",
                200_000,
                16_384,
                Set.of(TEXT, TOOL, new ModelCapability("structured-output")),
                Set.of(GLOBAL, CN),
                ACTOR,
                UtcTimestamp.parse("2026-08-23T00:11:00Z"));

        assertEquals(revisionOne.id(), revisionTwo.id());
        assertEquals(revisionOne.providerKey(), revisionTwo.providerKey());
        assertEquals(revisionOne.modelId(), revisionTwo.modelId());
        assertEquals(new ModelCatalogRevision(1), revisionOne.catalogRevision());
        assertEquals(new ModelCatalogRevision(2), revisionTwo.catalogRevision());
        assertEquals(Optional.of(revisionOne.catalogRevision()), revisionTwo.previousRevision());
        assertEquals(new ModelRevision("DeepSeek-V4-Flash-0901"), revisionTwo.modelRevision());
        assertNotEquals(revisionOne.contentHash(), revisionTwo.contentHash());
    }

    @Test
    void validatesCapabilitiesTokenLimitsAndProviderRegionSubset() {
        ModelProviderDefinition provider = provider();

        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        provider, 8_192, 16_384, Set.of(TEXT), Set.of(GLOBAL)));
        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        provider, 128_000, 8_192, Set.of(), Set.of(GLOBAL)));
        assertThrows(
                DomainValidationException.class,
                () -> publish(
                        provider,
                        128_000,
                        8_192,
                        Set.of(TEXT),
                        Set.of(new ModelRegion("eu"))));
    }

    @Test
    void disabledCatalogBlocksNewSelectionButHistoricalRevisionRemainsHashClosed() {
        ModelProviderDefinition provider = provider();
        ModelCatalogEntry active = catalog(provider);
        ModelCatalogEntry disabled = active.disable(
                ACTOR, UtcTimestamp.parse("2026-08-23T00:12:00Z"));

        assertEquals(active.contentHash(), disabled.contentHash());
        assertThrows(
                DomainValidationException.class,
                () -> disabled.requireSelectable(provider));

        ModelCatalogEntry restored = ModelCatalogEntry.reconstitute(
                provider,
                disabled.providerDefinitionHash(),
                disabled.id(),
                disabled.modelId(),
                disabled.catalogRevision(),
                disabled.previousRevision(),
                disabled.modelRevision(),
                disabled.displayName(),
                disabled.contextWindowTokens(),
                disabled.maximumOutputTokens(),
                disabled.capabilities(),
                disabled.availableRegions(),
                disabled.contentHash(),
                disabled.status(),
                disabled.lifecycleVersion(),
                disabled.audit());

        assertEquals(disabled.contentHash(), restored.contentHash());
        assertEquals(ModelRegistryStatus.DISABLED, restored.status());
    }

    @Test
    void rejectsForgedCatalogHashAndDifferentProviderDefinition() {
        ModelProviderDefinition provider = provider();
        ModelCatalogEntry entry = catalog(provider);

        assertThrows(
                DomainValidationException.class,
                () -> ModelCatalogEntry.reconstitute(
                        provider,
                        entry.providerDefinitionHash(),
                        entry.id(),
                        entry.modelId(),
                        entry.catalogRevision(),
                        entry.previousRevision(),
                        entry.modelRevision(),
                        entry.displayName(),
                        entry.contextWindowTokens(),
                        entry.maximumOutputTokens(),
                        entry.capabilities(),
                        entry.availableRegions(),
                        ModelRegistryHash.sha256("forged"),
                        entry.status(),
                        entry.lifecycleVersion(),
                        entry.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> ModelCatalogEntry.reconstitute(
                        provider,
                        ModelRegistryHash.sha256("forged-provider"),
                        entry.id(),
                        entry.modelId(),
                        entry.catalogRevision(),
                        entry.previousRevision(),
                        entry.modelRevision(),
                        entry.displayName(),
                        entry.contextWindowTokens(),
                        entry.maximumOutputTokens(),
                        entry.capabilities(),
                        entry.availableRegions(),
                        entry.contentHash(),
                        entry.status(),
                        entry.lifecycleVersion(),
                        entry.audit()));

        ModelProviderDefinition otherProvider = ModelProviderDefinition.publish(
                new ModelProviderKey("openai"),
                "OpenAI",
                new ModelAdapterKey("openai"),
                new ModelEndpoint("https://api.openai.com/v1"),
                Set.of(GLOBAL),
                ModelDataPolicy.noRetention(),
                ACTOR,
                CREATED_AT);
        assertThrows(
                DomainValidationException.class,
                () -> entry.publishNext(
                        otherProvider,
                        new ModelRevision("2026-08-23"),
                        "Forged",
                        128_000,
                        8_192,
                        Set.of(TEXT),
                        Set.of(GLOBAL),
                        ACTOR,
                        UtcTimestamp.parse("2026-08-23T00:13:00Z")));
        assertTrue(entry.previousRevision().isEmpty());
    }

    private static ModelCatalogEntry catalog(ModelProviderDefinition provider) {
        return publish(provider, 128_000, 8_192, Set.of(TEXT, TOOL), Set.of(GLOBAL, CN));
    }

    private static ModelCatalogEntry publish(
            ModelProviderDefinition provider,
            long contextWindow,
            long maximumOutput,
            Set<ModelCapability> capabilities,
            Set<ModelRegion> regions) {
        return ModelCatalogEntry.publishInitial(
                provider,
                ModelCatalogEntryId.generate(),
                new ModelId("deepseek-v4-flash"),
                new ModelRevision("DeepSeek-V4-Flash-0731"),
                "DeepSeek V4 Flash",
                contextWindow,
                maximumOutput,
                capabilities,
                regions,
                ACTOR,
                CREATED_AT);
    }

    private static ModelProviderDefinition provider() {
        return ModelProviderDefinition.publish(
                new ModelProviderKey("deepseek"),
                "DeepSeek",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.deepseek.com/v1"),
                Set.of(GLOBAL, CN),
                ModelDataPolicy.noRetention(),
                ACTOR,
                CREATED_AT);
    }
}
