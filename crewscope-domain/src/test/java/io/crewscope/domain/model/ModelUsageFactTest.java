package io.crewscope.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.model.event.ModelUsageFactRecorded;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Proves the frozen S01 §3.9 usage-fact shape: five roles, quadruple invariants, envelope. */
class ModelUsageFactTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T09:00:00Z");

    @Test
    void roleVocabularyIsExactlyTheFiveFrozenValues() {
        assertEquals(
                Arrays.asList("CHAT_PRIMARY", "CHAT_FALLBACK", "COMPACTION", "EMBEDDING", "DISTILLATION"),
                Arrays.stream(ModelUsageRole.values()).map(Enum::name).toList());
    }

    @Test
    void tokenQuadrupleEnforcesTheRuntimeInvariants() {
        new ModelTokenUsage(100, 40, 60, 140);
        new ModelTokenUsage(0, 0, 0, 0);
        assertEquals(new ModelTokenUsage(0, 0, 0, 0), ModelTokenUsage.unreported());

        assertThrows(IllegalArgumentException.class, () -> new ModelTokenUsage(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ModelTokenUsage(10, -2, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new ModelTokenUsage(10, 5, 11, 15));
        assertThrows(IllegalArgumentException.class, () -> new ModelTokenUsage(10, 5, 4, 16));
    }

    @Test
    void factRecordRejectsMalformedEnvelopeCoordinates() {
        ModelUsageFactRecorded wellFormed = new ModelUsageFactRecorded(
                ModelUsageFactId.generate(), ModelUsageRole.DISTILLATION, 1,
                new ModelProviderKey("openai"), new ModelId("gpt-5"),
                ModelConnectionId.generate(), 3,
                new ModelTokenUsage(1200, 80, 900, 1280), NOW);
        assertEquals(ModelUsageRole.DISTILLATION, wellFormed.role());
        assertEquals(1, wellFormed.attempt());

        assertThrows(
                NullPointerException.class,
                () -> new ModelUsageFactRecorded(
                        null, ModelUsageRole.DISTILLATION, 1,
                        new ModelProviderKey("openai"), new ModelId("gpt-5"),
                        ModelConnectionId.generate(), 3,
                        new ModelTokenUsage(0, 0, 0, 0), NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelUsageFactRecorded(
                        ModelUsageFactId.generate(), ModelUsageRole.DISTILLATION, 0,
                        new ModelProviderKey("openai"), new ModelId("gpt-5"),
                        ModelConnectionId.generate(), 3,
                        new ModelTokenUsage(0, 0, 0, 0), NOW));
        assertThrows(
                NullPointerException.class,
                () -> new ModelUsageFactRecorded(
                        ModelUsageFactId.generate(), null, 1,
                        new ModelProviderKey("openai"), new ModelId("gpt-5"),
                        ModelConnectionId.generate(), 3,
                        new ModelTokenUsage(0, 0, 0, 0), NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelUsageFactRecorded(
                        ModelUsageFactId.generate(), ModelUsageRole.DISTILLATION, 1,
                        new ModelProviderKey("openai"), new ModelId("gpt-5"),
                        ModelConnectionId.generate(), -1,
                        new ModelTokenUsage(0, 0, 0, 0), NOW));
    }

    @Test
    void factIdKeepsTheCanonicalAggregateContract() {
        ModelUsageFactId generated = ModelUsageFactId.generate();
        assertEquals(generated, ModelUsageFactId.from(generated.value().toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelUsageFactId.from("not-a-uuid"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelUsageFactId.from(""));
        assertThrows(
                NullPointerException.class,
                () -> new ModelUsageFactId(null));
    }
}
