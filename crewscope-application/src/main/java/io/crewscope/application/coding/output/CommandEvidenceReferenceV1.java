package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.CANONICAL_UUID;
import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Exact model reference to one platform-owned CommandEvidence fact. */
public record CommandEvidenceReferenceV1(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = CANONICAL_UUID) String commandEvidenceId,
        @JsonProperty(required = true) @Min(1) long sequence,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String evidenceHash) {}
