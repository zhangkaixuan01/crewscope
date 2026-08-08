package io.crewscope.application.conversation;

import static io.crewscope.application.conversation.StructuredOutputPatterns.FIELD_KEY;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One stable, answerable question inside a ClarificationRequestV1. */
public record ClarificationQuestionV1(
        @NotBlank @Pattern(regexp = FIELD_KEY) String fieldKey,
        @NotBlank @Size(max = 500) String question,
        @Size(max = 1_000) String context,
        boolean required,
        @NotNull @Size(max = 5) List<@NotBlank @Size(max = 200) String> choices) {

    public ClarificationQuestionV1 {
        choices = choices == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(choices));
    }
}
