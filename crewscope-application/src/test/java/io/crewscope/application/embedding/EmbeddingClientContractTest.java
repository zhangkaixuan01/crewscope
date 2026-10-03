package io.crewscope.application.embedding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelTokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Frozen S01 §3.2 numbers enforced at the port boundary before any HTTP is spent. */
final class EmbeddingClientContractTest {

    private static EmbeddingClient.EmbeddingRequest request(List<String> input) {
        return new EmbeddingClient.EmbeddingRequest("text-embedding-v4", 1024, input);
    }

    @Test
    void acceptsExactlyTheMeasuredBatchWindow() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < EmbeddingClient.MAX_BATCH; i++) {
            ten.add("item");
        }
        assertDoesNotThrow(() -> request(ten));
        ten.add("one too many");
        assertThrows(IllegalArgumentException.class, () -> request(ten));
    }

    @Test
    void rejectsEmptyAndBlankItemsWithoutAnHttpRoundTrip() {
        assertThrows(IllegalArgumentException.class, () -> request(List.of()));
        assertThrows(IllegalArgumentException.class, () -> request(List.of("ok", "")));
        assertThrows(IllegalArgumentException.class, () -> request(List.of("ok", "   ")));
    }

    @Test
    void rejectsItemsBeyondTheMeasuredCharacterCap() {
        String within = "a".repeat(EmbeddingClient.MAX_INPUT_CHARS);
        assertDoesNotThrow(() -> request(List.of(within)));
        String beyond = "a".repeat(EmbeddingClient.MAX_INPUT_CHARS + 1);
        assertThrows(IllegalArgumentException.class, () -> request(List.of(beyond)));
    }

    @Test
    void callOutcomeMustCarryExactlyOneSanitizedShape() {
        List<EmbeddingClient.Attempt> attempts = List.of(
                new EmbeddingClient.Attempt(1, true, ModelTokenUsage.unreported()));
        assertThrows(IllegalArgumentException.class, () ->
                new EmbeddingClient.EmbeddingCall(true,
                        Optional.of(ModelConnectionHealthFailureCode.RATE_LIMITED),
                        List.of(new float[1024]), attempts));
        assertThrows(IllegalArgumentException.class, () ->
                new EmbeddingClient.EmbeddingCall(false, Optional.empty(), List.of(), attempts));
        assertThrows(IllegalArgumentException.class, () ->
                new EmbeddingClient.EmbeddingCall(false,
                        Optional.of(ModelConnectionHealthFailureCode.RATE_LIMITED),
                        List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new EmbeddingClient.EmbeddingCall(true, Optional.empty(), List.of(), List.of()));
    }
}
