package io.crewscope.server.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Start-rebuild body: pointer and definition versions plus the confirmation phrase. */
public final class StartRebuildRequest {

    private final Long expectedDefinitionVersion;
    private final Long expectedPointerVersion;
    private final String confirmation;

    @JsonCreator
    public StartRebuildRequest(
            @JsonProperty("expectedDefinitionVersion") Long expectedDefinitionVersion,
            @JsonProperty("expectedPointerVersion") Long expectedPointerVersion,
            @JsonProperty("confirmation") String confirmation) {
        this.expectedDefinitionVersion = expectedDefinitionVersion;
        this.expectedPointerVersion = expectedPointerVersion;
        this.confirmation = confirmation;
    }

    public Long expectedDefinitionVersion() { return expectedDefinitionVersion; }

    public Long expectedPointerVersion() { return expectedPointerVersion; }

    public String confirmation() { return confirmation; }

    @JsonAnySetter
    void rejectUnknownProperty(String ignoredProperty, Object ignoredValue) {
        throw new IllegalArgumentException("Unsupported Projection rebuild property");
    }
}
