package io.crewscope.application.activity;

import java.util.Objects;
import java.util.regex.Pattern;

/** Maps one reviewed DomainEvent payload path to one public Activity payload field. */
public record ActivityPayloadFieldMapping(
        String publicField, String sourcePath, boolean required) {

    private static final Pattern SOURCE_PATH =
            Pattern.compile("[a-z][A-Za-z0-9]*(?:\\.[a-z][A-Za-z0-9]*)*");

    public ActivityPayloadFieldMapping {
        publicField = requireText(publicField, "publicField");
        sourcePath = requireText(sourcePath, "sourcePath");
        if (!SOURCE_PATH.matcher(sourcePath).matches()) {
            throw new IllegalArgumentException("Activity payload sourcePath has an invalid format");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
