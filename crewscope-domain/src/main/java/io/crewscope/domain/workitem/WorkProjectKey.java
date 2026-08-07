package io.crewscope.domain.workitem;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable upper-case key used as the prefix of every WorkItem in a project. */
public record WorkProjectKey(String value) {

    public static final String FORMAT_REGEX = "[A-Z][A-Z0-9]{1,9}";

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public WorkProjectKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(
                    "workProject.key", "must match " + FORMAT_REGEX);
        }
    }
}
