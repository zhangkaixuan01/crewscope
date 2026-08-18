package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.CANONICAL_UUID;
import static io.crewscope.application.coding.output.CodingOutputPatterns.REPOSITORY_PATH;
import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;
import static io.crewscope.application.coding.output.CodingOutputPatterns.VERSION_ONE;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Untrusted, bounded repository analysis proposed by the Coding Specialist. */
public record RepositoryAnalysisV1(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = VERSION_ONE)
                String schemaVersion,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID)
                String codingTargetSnapshotId,
        @JsonProperty(required = true) @Min(1) @Max(Long.MAX_VALUE)
                long codingTargetRevision,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256)
                String codingTargetHash,
        @JsonProperty(required = true) @NotNull @Size(max = 100)
                List<@NotBlank @Size(max = 200) String> modules,
        @JsonProperty(required = true) @NotNull @Size(max = 50)
                List<@NotBlank @Pattern(regexp = REPOSITORY_PATH) String> buildEntries,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 500)
                List<@NotBlank @Pattern(regexp = REPOSITORY_PATH) String> relevantPaths,
        @JsonProperty(required = true) @NotNull @Size(max = 50)
                List<@NotBlank @Size(max = 1_000) String> risks,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100)
                List<@NotBlank @Size(max = 1_000) String> plan) {

    public static final String SCHEMA_VERSION = VERSION_ONE;

    public RepositoryAnalysisV1 {
        modules = immutable(modules);
        buildEntries = immutable(buildEntries);
        relevantPaths = immutable(relevantPaths);
        risks = immutable(risks);
        plan = immutable(plan);
    }

    private static <T> List<T> immutable(List<T> value) {
        return value == null ? null : List.copyOf(value);
    }
}
