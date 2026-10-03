package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelProviderDefinition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional second leg of model connection verification (M10-I01a, S01 §3.2): the
 * transport probe proves reachability and authentication, but a {@code GET /models} 200
 * cannot prove embedding capability — the measured provider omits its embedding model
 * from that list. This probe sends one minimal real embedding request and validates the
 * delivered vector, so its result may be recorded into connection health.
 *
 * <p>Returns {@link Optional#empty()} when the provider has no ACTIVE embedding catalog
 * entry: verification then behaves exactly as before this port existed.
 */
public interface EmbeddingCapabilityProbe {

    /** Fixed minimal probe text; small enough to spend only trace tokens per verify. */
    String PROBE_INPUT = "crewscope embedding capability probe";

    /**
     * The absent leg: verification behaves exactly as before this port existed — the
     * transport probe alone decides the recorded health. Injected where no embedding
     * probe is assembled, so the field never has to tolerate {@code null}.
     */
    EmbeddingCapabilityProbe ABSENT =
            (provider, connection, credentialHandle, correlationId) -> Optional.empty();

    /**
     * Probes one connection with the already-opened verification handle. Must not throw —
     * delivery failures are the {@link Outcome} payload, and exceptions are sanitized by
     * the caller exactly like the transport probe.
     */
    Optional<Outcome> probeEmbeddingCapability(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ProviderCredentialHandle credentialHandle,
            UUID correlationId);

    /** Sanitized probe verdict mirroring the transport probe's result shape. */
    record Outcome(boolean healthy, Optional<ModelConnectionHealthFailureCode> failureCode) {

        public Outcome {
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            if (healthy == failureCode.isPresent()) {
                throw new IllegalArgumentException("Probe outcome shape is invalid");
            }
        }

        public static Outcome success() {
            return new Outcome(true, Optional.empty());
        }

        public static Outcome failed(ModelConnectionHealthFailureCode failureCode) {
            return new Outcome(false, Optional.of(failureCode));
        }
    }
}
