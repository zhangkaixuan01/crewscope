package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Retry-rebuild body: previous job version plus the shared rebuild expectations. */
public final class RetryRebuildRequest {

    private final Long expectedRetryOfJobVersion;
    private final Long expectedDefinitionVersion;
    private final Long expectedPointerVersion;
    private final String confirmation;

    @JsonCreator
    public RetryRebuildRequest(
            @JsonProperty("expectedRetryOfJobVersion") Long expectedRetryOfJobVersion,
            @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
            @JsonProperty("expectedPointerVersion") Long expectedPointerVersion,
            @JsonProperty("confirmation") String confirmation) {
        this.expectedRetryOfJobVersion = expectedRetryOfJobVersion;
        this.expectedDefinitionVersion = expectedDefinitionVersion;
        this.expectedPointerVersion = expectedPointerVersion;
        this.confirmation = confirmation;
    }

    public Long expectedRetryOfJobVersion() { return expectedRetryOfJobVersion; }

    public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

    public Long expectedPointerVersion() { return expectedPointerVersion; }

    public String confirmation() { return confirmation; }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported Projection retry property");
    }
}
