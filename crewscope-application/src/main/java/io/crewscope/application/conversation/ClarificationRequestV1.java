package io.crewscope.application.conversation;

import static io.crewscope.application.conversation.StructuredOutputPatterns.VERSION_ONE;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Untrusted Agent structured output requesting bounded user clarification before an intent. */
public record ClarificationRequestV1(
        @NotBlank @Pattern(regexp = VERSION_ONE) String schemaVersion,
        @NotBlank @Size(max = 1_000) String summary,
        @NotNull @Size(min = 1, max = 10) @Valid List<ClarificationQuestionV1> questions) {

    public static final String SCHEMA_VERSION = VERSION_ONE;

    public ClarificationRequestV1 {
        questions = questions == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(questions));
    }
}
