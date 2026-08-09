package io.crewscope.application.execution;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable runtime implementation identity pinned into policy and audit facts. */
public record RuntimeDescriptor(String runtimeId, String displayName, String version) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?");

    public RuntimeDescriptor {
        runtimeId = require(runtimeId, "runtimeId", 64);
        displayName = require(displayName, "displayName", 120);
        version = require(version, "version", 64);
        if (!ID.matcher(runtimeId).matches()) {
            throw new IllegalArgumentException("runtimeId must use a stable kebab-case identifier");
        }
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("version must use a semantic numeric version");
        }
    }

    private static String require(String value, String field, int maxLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + maxLength + " characters");
        }
        return required;
    }
}
