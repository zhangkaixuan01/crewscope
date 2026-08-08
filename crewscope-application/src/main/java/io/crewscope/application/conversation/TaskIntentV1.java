package io.crewscope.application.conversation;

import static io.crewscope.application.conversation.StructuredOutputPatterns.CANONICAL_UUID;
import static io.crewscope.application.conversation.StructuredOutputPatterns.VERSION_ONE;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Untrusted Agent structured output describing a version-one TaskIntent proposal. */
public record TaskIntentV1(
        @NotBlank @Pattern(regexp = VERSION_ONE) String schemaVersion,
        @NotBlank @Size(max = 5_000) String objective,
        @NotNull @Size(min = 1, max = 20)
                List<@NotBlank @Size(max = 1_000) String> acceptanceCriteria,
        @NotBlank @Pattern(regexp = CANONICAL_UUID) String workProjectId,
        @NotBlank @Pattern(regexp = CANONICAL_UUID) String ownerMemberId,
        @Pattern(regexp = CANONICAL_UUID) String executorPrincipalId,
        @Pattern(regexp = CANONICAL_UUID) String gateReviewerMemberId) {

    public static final String SCHEMA_VERSION = VERSION_ONE;

    public TaskIntentV1 {
        acceptanceCriteria = immutableNullable(acceptanceCriteria);
    }

    private static <T> List<T> immutableNullable(List<T> values) {
        return values == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
