package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.CANONICAL_UUID;
import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;
import static io.crewscope.application.coding.output.CodingOutputPatterns.VERSION_ONE;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Untrusted model declaration of a Git-authority DiffManifest. */
public record DiffManifestV1(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = VERSION_ONE) String schemaVersion,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String executionWorkspaceId,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String workspaceFingerprint,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String codingTargetSnapshotId,
        @JsonProperty(required = true) @Min(1) long codingTargetRevision,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String codingTargetHash,
        @JsonProperty(required = true) @Min(1) long diffGeneration,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String manifestHash,
        @JsonProperty(required = true) @Min(0) @Max(10_000) int fileCount,
        @JsonProperty(required = true) @Min(0) long additions,
        @JsonProperty(required = true) @Min(0) long deletions,
        @JsonProperty(required = true) @NotNull @Size(max = 10_000) @Valid List<DiffFileV1> files) {

    public static final String SCHEMA_VERSION = VERSION_ONE;

    public DiffManifestV1 {
        files = files == null ? null : List.copyOf(files);
    }
}
