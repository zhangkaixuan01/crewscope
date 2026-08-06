package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

public record WorkItemKey(String value) {

    /** Canonical key syntax shared by the domain object and external Command validation. */
    public static final String FORMAT_REGEX = "[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*";

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public WorkItemKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(
                    "workItem.key", "must match " + FORMAT_REGEX);
        }
    }
}
