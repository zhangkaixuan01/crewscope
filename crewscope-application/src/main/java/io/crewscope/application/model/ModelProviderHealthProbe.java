package io.crewscope.application.model;

import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelProviderDefinition;
import java.util.Objects;
import java.util.Optional;

/** Outbound Port for one sanitized provider authentication and reachability probe. */
public interface ModelProviderHealthProbe {

    ProbeResult probe(
            ModelProviderDefinition provider,
            ModelConnection connection,
            ProviderCredentialHandle credentialHandle);

    /** Stable result that intentionally excludes endpoint, headers and provider response bodies. */
    record ProbeResult(boolean healthy, Optional<ModelConnectionHealthFailureCode> failureCode) {

        public ProbeResult {
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            if (healthy == failureCode.isPresent()) {
                throw new IllegalArgumentException("Probe result shape is invalid");
            }
        }

        public static ProbeResult success() {
            return new ProbeResult(true, Optional.empty());
        }

        public static ProbeResult failed(ModelConnectionHealthFailureCode failureCode) {
            return new ProbeResult(false, Optional.of(Objects.requireNonNull(failureCode)));
        }
    }
}
