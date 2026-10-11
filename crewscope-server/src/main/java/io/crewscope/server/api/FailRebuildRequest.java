package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Fail-rebuild body: expected versions, the failure code and the confirmation phrase. */
public final class FailRebuildRequest implements TerminateRequest {

    private final Long expectedGenerationVersion;
    private final Long expectedJobVersion;
    private final String failureCode;
    private final String confirmation;

    @JsonCreator
    public FailRebuildRequest(
            @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion,
            @JsonProperty("expectedJobVersion") Long expectedJobVersion,
            @JsonProperty("failureCode") String failureCode,
            @JsonProperty("confirmation") String confirmation) {
        this.expectedGenerationVersion = expectedGenerationVersion;
        this.expectedJobVersion = expectedJobVersion;
        this.failureCode = failureCode;
        this.confirmation = confirmation;
    }

    @Override
    public Long expectedGenerationVersion() { return expectedGenerationVersion; }

    @Override
    public Long expectedJobVersion() { return expectedJobVersion; }

    public String failureCode() { return failureCode; }

    @Override
    public String confirmation() { return confirmation; }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported Projection failure property");
    }
}
