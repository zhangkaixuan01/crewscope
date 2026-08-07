package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Locale;

/** Canonical lower-case label used for filtering and board grouping. */
public record WorkItemLabel(String value) {

    public static final int MAX_LENGTH = 50;

    public WorkItemLabel {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("workItem.label", "must not be blank");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "workItem.label", "must contain at most " + MAX_LENGTH + " characters");
        }
    }
}
