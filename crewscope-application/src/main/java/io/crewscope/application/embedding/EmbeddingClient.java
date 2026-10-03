package io.crewscope.application.embedding;

import io.crewscope.application.model.ProviderCredentialHandle;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelTokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outbound Port for one minimal OpenAI-compatible {@code POST /embeddings} call
 * (M10-S01 §3.2): an independent transport that shares the credential-handle and
 * outbound-policy governance with the health probe but none of the chat-completions
 * request-body assembly — the chat adapter must never be reused for embeddings.
 *
 * <p>Calls are synchronous and batched. Every real HTTP attempt reports exactly one
 * {@link Attempt} so failed-attempt token usage is never lost, and response vectors are
 * validated for dimension and finiteness before delivery is claimed.
 */
public interface EmbeddingClient {

    /** Product-wide batch hard cap (S01b measured: batches above 10 are rejected 400). */
    int MAX_BATCH = 10;

    /** Per-item input cap in characters (S01b measured 33000; the documented 8192 is stale). */
    int MAX_INPUT_CHARS = 33_000;

    /** Minimum request timeout (measured single-item P99 569.7ms plus 3x headroom). */
    Duration MINIMUM_REQUEST_TIMEOUT = Duration.ofSeconds(1);

    EmbeddingCall embed(
            ModelConnection connection,
            EmbeddingRequest request,
            ProviderCredentialHandle credentialHandle);

    /** One batch request against the connection's provider endpoint. */
    record EmbeddingRequest(String model, int dimensions, List<String> input) {

        public EmbeddingRequest {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(input, "input");
            if (dimensions < 1) {
                throw new IllegalArgumentException("dimensions must be positive");
            }
            if (input.isEmpty() || input.size() > MAX_BATCH) {
                throw new IllegalArgumentException(
                        "input must contain between 1 and " + MAX_BATCH + " items");
            }
            for (String item : input) {
                if (item == null || item.isBlank()) {
                    throw new IllegalArgumentException("input items must not be blank");
                }
                if (item.length() > MAX_INPUT_CHARS) {
                    throw new IllegalArgumentException(
                            "input items must not exceed " + MAX_INPUT_CHARS + " characters");
                }
            }
        }
    }

    /**
     * Terminal outcome of one batch call. {@code delivered=false} carries the sanitized
     * failure code; {@code delivered=true} guarantees one validated vector per input item.
     */
    record EmbeddingCall(
            boolean delivered,
            Optional<ModelConnectionHealthFailureCode> failureCode,
            List<float[]> vectors,
            List<Attempt> attempts) {

        public EmbeddingCall {
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            if (delivered == failureCode.isPresent()) {
                throw new IllegalArgumentException("Embedding call shape is invalid");
            }
            vectors = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
            attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
            if (delivered && vectors.isEmpty()) {
                throw new IllegalArgumentException("A delivered call carries its vectors");
            }
            if (attempts.isEmpty()) {
                throw new IllegalArgumentException("At least one real attempt is required");
            }
        }
    }

    /** One real provider HTTP attempt; unreported usage means the provider echoed none. */
    record Attempt(int attempt, boolean delivered, ModelTokenUsage usage) {

        public Attempt {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            usage = Objects.requireNonNull(usage, "usage");
        }
    }
}
