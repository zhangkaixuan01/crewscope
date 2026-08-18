package io.crewscope.application.coding.output;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

/** Parser-observed test counters repeated by the model for authority comparison. */
public record TestStatisticsV1(
        @JsonProperty(required = true) @Min(0) long total,
        @JsonProperty(required = true) @Min(0) long passed,
        @JsonProperty(required = true) @Min(0) long failed,
        @JsonProperty(required = true) @Min(0) long errors,
        @JsonProperty(required = true) @Min(0) long skipped) {}
