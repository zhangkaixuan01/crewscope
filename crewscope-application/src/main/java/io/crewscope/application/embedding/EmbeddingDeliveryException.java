package io.crewscope.application.embedding;

import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import java.util.Objects;

/**
 * Terminal sanitized failure of one embedding batch delivery. The failure code mirrors the
 * connection-health vocabulary (never the provider response body) so the caller can route
 * it into telemetry and operator dashboards unchanged.
 */
public final class EmbeddingDeliveryException extends RuntimeException {

    private final ModelConnectionHealthFailureCode failureCode;

    public EmbeddingDeliveryException(ModelConnectionHealthFailureCode failureCode) {
        super("embedding delivery failed: " + Objects.requireNonNull(failureCode, "failureCode"));
        this.failureCode = failureCode;
    }

    public ModelConnectionHealthFailureCode failureCode() {
        return failureCode;
    }
}
