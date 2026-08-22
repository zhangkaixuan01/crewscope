package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.VERSION_ONE;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Model-authored prose fields used to build the platform-owned CodeChangeResultV1. */
public record CodingDeliverySummaryV1(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = VERSION_ONE)
                String schemaVersion,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100)
                List<@NotBlank @Size(max = 1_000) String> changeSummary,
        @JsonProperty(required = true) @NotNull @Size(max = 50)
                List<@NotBlank @Size(max = 1_000) String> limitations,
        @JsonProperty(required = true) @NotNull @Size(max = 50)
                List<@NotBlank @Size(max = 1_000) String> risks) {

    public static final String SCHEMA_VERSION = VERSION_ONE;

    public CodingDeliverySummaryV1 {
        changeSummary = immutable(changeSummary);
        limitations = immutable(limitations);
        risks = immutable(risks);
    }

    private static <T> List<T> immutable(List<T> value) {
        return value == null ? null : List.copyOf(value);
    }
}
