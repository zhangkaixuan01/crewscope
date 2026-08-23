package io.crewscope.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelProviderDefinitionTest {

    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-23T00:00:00Z");
    private static final ModelRegion GLOBAL = new ModelRegion("global");
    private static final ModelRegion CN = new ModelRegion("cn");

    @Test
    void keepsProductProviderSeparateFromTrustedRuntimeAdapterAndVerifiesHash() {
        ModelProviderDefinition deepSeek = provider();

        ModelProviderDefinition restored = ModelProviderDefinition.reconstitute(
                deepSeek.providerKey(),
                deepSeek.displayName(),
                deepSeek.adapterKey(),
                deepSeek.defaultEndpoint(),
                deepSeek.availableRegions(),
                deepSeek.dataPolicy(),
                deepSeek.contentHash(),
                deepSeek.status(),
                deepSeek.lifecycleVersion(),
                deepSeek.audit());

        assertEquals(new ModelProviderKey("deepseek"), restored.providerKey());
        assertEquals(new ModelAdapterKey("openai-compatible"), restored.adapterKey());
        assertEquals(deepSeek.contentHash(), restored.contentHash());
        assertThrows(
                DomainValidationException.class,
                () -> ModelProviderDefinition.reconstitute(
                        deepSeek.providerKey(),
                        deepSeek.displayName(),
                        deepSeek.adapterKey(),
                        deepSeek.defaultEndpoint(),
                        deepSeek.availableRegions(),
                        deepSeek.dataPolicy(),
                        ModelRegistryHash.sha256("forged"),
                        deepSeek.status(),
                        deepSeek.lifecycleVersion(),
                        deepSeek.audit()));
    }

    @Test
    void validatesEndpointRegionsAndRetentionPolicy() {
        assertThrows(
                DomainValidationException.class,
                () -> new ModelEndpoint("https://secret@example.com/v1?api_key=hidden"));
        assertThrows(
                DomainValidationException.class,
                () -> new ModelDataPolicy(
                        ModelDataRetentionMode.TIME_BOUND,
                        Optional.empty(),
                        ModelTrainingUsagePolicy.PROHIBITED));
        assertThrows(
                DomainValidationException.class,
                () -> new ModelDataPolicy(
                        ModelDataRetentionMode.NONE,
                        Optional.of(Duration.ofHours(1)),
                        ModelTrainingUsagePolicy.PROHIBITED));
        assertThrows(
                DomainValidationException.class,
                () -> new ModelDataPolicy(
                        ModelDataRetentionMode.TIME_BOUND,
                        Optional.of(Duration.ofSeconds(1, 1)),
                        ModelTrainingUsagePolicy.PROHIBITED));
        assertThrows(
                DomainValidationException.class,
                () -> ModelProviderDefinition.publish(
                        new ModelProviderKey("empty-region"),
                        "Empty region",
                        new ModelAdapterKey("openai-compatible"),
                        new ModelEndpoint("https://example.com/v1"),
                        Set.of(),
                        ModelDataPolicy.noRetention(),
                        ACTOR,
                        CREATED_AT));
    }

    @Test
    void lifecycleDoesNotRewriteProviderContentAndDisabledProviderFailsClosed() {
        ModelProviderDefinition active = provider();
        ModelProviderDefinition disabled = active.disable(
                ACTOR, UtcTimestamp.parse("2026-08-23T00:01:00Z"));

        assertEquals(ModelRegistryStatus.DISABLED, disabled.status());
        assertEquals(active.contentHash(), disabled.contentHash());
        assertEquals(1, disabled.lifecycleVersion());
        assertThrows(DomainValidationException.class, disabled::requireSelectable);
        ModelProviderDefinition archived = disabled.archive(
                ACTOR, UtcTimestamp.parse("2026-08-23T00:02:00Z"));
        assertThrows(
                DomainValidationException.class,
                () -> archived.activate(
                        ACTOR, UtcTimestamp.parse("2026-08-23T00:03:00Z")));
    }

    private static ModelProviderDefinition provider() {
        return ModelProviderDefinition.publish(
                new ModelProviderKey("deepseek"),
                "DeepSeek",
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.deepseek.com/v1"),
                Set.of(GLOBAL, CN),
                new ModelDataPolicy(
                        ModelDataRetentionMode.TIME_BOUND,
                        Optional.of(Duration.ofDays(30)),
                        ModelTrainingUsagePolicy.PROHIBITED),
                ACTOR,
                CREATED_AT);
    }
}
