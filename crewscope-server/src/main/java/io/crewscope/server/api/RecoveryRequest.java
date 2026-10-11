package io.crewscope.server.api;

import static io.crewscope.server.api.OperationsRequestParser.requireBody;
import static io.crewscope.server.api.OperationsRequestParser.requireText;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Recovery command body: one diagnostics-provided target plus its confirmation phrase. */
public final class RecoveryRequest {

    private final RecoveryTargetRequest target;
    private final String confirmation;

    @JsonCreator
    public RecoveryRequest(
            @JsonProperty("target") RecoveryTargetRequest target,
            @JsonProperty("confirmation") String confirmation) {
        this.target = requireBody(target, "target");
        this.confirmation = requireText(confirmation, "confirmation");
    }

    public RecoveryTargetRequest target() {
        return target;
    }

    public String confirmation() {
        return confirmation;
    }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported operations recovery property");
    }
}
