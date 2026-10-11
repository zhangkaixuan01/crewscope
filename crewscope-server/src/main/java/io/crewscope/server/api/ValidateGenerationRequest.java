package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Generation validation body: rebuild job and both expected generation/job versions. */
public final class ValidateGenerationRequest {

    private final Long expectedDefinitionVersion;
    private final String rebuildJobId;
    private final Long expectedGenerationVersion;
    private final Long expectedJobVersion;
    private final String confirmation;

    @JsonCreator
    public ValidateGenerationRequest(
            @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
            @JsonProperty("rebuildJobId") String rebuildJobId,
            @JsonProperty("expectedGenerationVersion") Long expectedGenerationVersion,
            @JsonProperty("expectedJobVersion") Long expectedJobVersion,
            @JsonProperty("confirmation") String confirmation) {
        this.expectedDefinitionVersion = expectedDefinitionVersion;
        this.rebuildJobId = rebuildJobId;
        this.expectedGenerationVersion = expectedGenerationVersion;
        this.expectedJobVersion = expectedJobVersion;
        this.confirmation = confirmation;
    }

    public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

    public String rebuildJobId() { return rebuildJobId; }

    public Long expectedGenerationVersion() { return expectedGenerationVersion; }

    public Long expectedJobVersion() { return expectedJobVersion; }

    public String confirmation() { return confirmation; }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported Projection validation property");
    }
}
