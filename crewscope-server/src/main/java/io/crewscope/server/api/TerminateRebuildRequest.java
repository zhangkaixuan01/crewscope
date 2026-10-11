package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Cancel-rebuild body: expected versions plus the confirmation phrase. */
public final class TerminateRebuildRequest implements TerminateRequest {

    private final Long expectedGenerationVersion;
    private final Long expectedJobVersion;
    private final String confirmation;

    @JsonCreator
    public TerminateRebuildRequest(
            @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion,
            @JsonProperty("expectedJobVersion") Long expectedJobVersion,
            @JsonProperty("confirmation") String confirmation) {
        this.expectedGenerationVersion = expectedGenerationVersion;
        this.expectedJobVersion = expectedJobVersion;
        this.confirmation = confirmation;
    }

    @Override
    public Long expectedGenerationVersion() { return expectedGenerationVersion; }

    @Override
    public Long expectedJobVersion() { return expectedJobVersion; }

    @Override
    public String confirmation() { return confirmation; }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported Projection cancellation property");
    }
}
