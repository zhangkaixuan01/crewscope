package io.crewscope.application.coding.output;

import static io.crewscope.application.coding.output.CodingOutputPatterns.REPOSITORY_PATH;
import static io.crewscope.application.coding.output.CodingOutputPatterns.SHA_256;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** One model-declared Diff entry; the empty oldPath value means the change is not a rename/copy. */
public record DiffFileV1(
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = REPOSITORY_PATH) String path,
        @JsonProperty(required = true) @Pattern(regexp = "|" + REPOSITORY_PATH) String oldPath,
        @JsonProperty(required = true) @NotBlank
                @Pattern(regexp = "ADDED|MODIFIED|DELETED|RENAMED|COPIED|TYPE_CHANGED") String kind,
        @JsonProperty(required = true) @Min(0) long additions,
        @JsonProperty(required = true) @Min(0) long deletions,
        @JsonProperty(required = true) boolean binary,
        @JsonProperty(required = true) boolean patchTruncated,
        @JsonProperty(required = true) @NotBlank @Pattern(regexp = SHA_256) String patchSha256) {}
