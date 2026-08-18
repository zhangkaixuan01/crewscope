package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Model repetition of one server-owned acceptance verdict and its exact evidence references. */
public record AcceptanceResultV1(
        @JsonProperty(required = true) @Min(1) int criterionIndex,
        @JsonProperty(required = true) @NotBlank @Size(max = 2_000) String criterion,
        @JsonProperty(required = true) @NotBlank
                @Pattern(regexp = "PASSED|FAILED|NOT_EVALUATED") String status,
        @JsonProperty(required = true) @NotNull @Size(max = 100) @Valid
                List<CommandEvidenceReferenceV1> evidence,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String summaryHash) {

    public AcceptanceResultV1 {
        evidence = evidence == null ? null : List.copyOf(evidence);
    }
}
