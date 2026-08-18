package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.CANONICAL_UUID;
import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;
import static io.crewscope.application.coding.output.CodingOutputPatterns.VERSION_ONE;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Untrusted model reference to immutable platform-owned test evidence. */
public record TestEvidenceV1(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = VERSION_ONE) String schemaVersion,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String testEvidenceId,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String evidenceHash,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String executionWorkspaceId,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String workspaceFingerprint,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String codingTargetSnapshotId,
        @JsonProperty(required = true) @Min(1) long codingTargetRevision,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String codingTargetHash,
        @JsonProperty(required = true) @Min(1) long diffGeneration,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String diffManifestHash,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String workspacePolicyId,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String workspacePolicyHash,
        @JsonProperty(required = true) @Min(1) long evidenceSequence,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100) @Valid
                List<CommandEvidenceReferenceV1> commands,
        @JsonProperty(required = true) @NotNull @Valid TestStatisticsV1 statistics,
        @JsonProperty(required = true) @NotNull @Size(min = 1, max = 100) @Valid
                List<AcceptanceResultV1> acceptanceResults,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String summaryHash) {

    public static final String SCHEMA_VERSION = VERSION_ONE;

    public TestEvidenceV1 {
        commands = commands == null ? null : List.copyOf(commands);
        acceptanceResults = acceptanceResults == null ? null : List.copyOf(acceptanceResults);
    }
}
