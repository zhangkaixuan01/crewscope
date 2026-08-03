package io.crewscope.domain.workitem;

import java.util.Objects;
import java.util.regex.Pattern;

public record WorkItemKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*");

    public WorkItemKey {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid WorkItem key: " + value);
        }
    }
}
