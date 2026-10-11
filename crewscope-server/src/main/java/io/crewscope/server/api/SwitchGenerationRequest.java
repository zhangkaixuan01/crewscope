package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Generation switch body: previous and target generation coordinates with versions. */
public final class SwitchGenerationRequest {

    private final Long expectedDefinitionVersion;
    private final Long previousActiveGeneration;
    private final String rebuildJobId;
    private final Long expectedPointerVersion;
    private final Long expectedPreviousGenerationVersion;
    private final Long expectedTargetGenerationVersion;
    private final Long expectedJobVersion;
    private final String confirmation;

    @JsonCreator
    public SwitchGenerationRequest(
            @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
            @JsonProperty("previousActiveGeneration") Long previousActiveGeneration,
            @JsonProperty("rebuildJobId") String rebuildJobId,
            @JsonProperty("expectedPointerVersion") Long expectedPointerVersion,
            @JsonProperty("expectedPreviousGenerationVersion")
                    Long expectedPreviousGenerationVersion,
            @JsonProperty("expectedTargetGenerationVersion")
                    Long expectedTargetGenerationVersion,
            @JsonProperty("expectedJobVersion") Long expectedJobVersion,
            @JsonProperty("confirmation") String confirmation) {
        this.expectedDefinitionVersion = expectedDefinitionVersion;
        this.previousActiveGeneration = previousActiveGeneration;
        this.rebuildJobId = rebuildJobId;
        this.expectedPointerVersion = expectedPointerVersion;
        this.expectedPreviousGenerationVersion = expectedPreviousGenerationVersion;
        this.expectedTargetGenerationVersion = expectedTargetGenerationVersion;
        this.expectedJobVersion = expectedJobVersion;
        this.confirmation = confirmation;
    }

    public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

    public Long previousActiveGeneration() { return previousActiveGeneration; }

    public String rebuildJobId() { return rebuildJobId; }

    public Long expectedPointerVersion() { return expectedPointerVersion; }

    public Long expectedPreviousGenerationVersion() {
        return expectedPreviousGenerationVersion;
    }

    public Long expectedTargetGenerationVersion() { return expectedTargetGenerationVersion; }

    public Long expectedJobVersion() { return expectedJobVersion; }

    public String confirmation() { return confirmation; }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported Projection switch property");
    }
}
