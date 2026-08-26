package io.crewscope.application.audit;

import java.util.Objects;
import java.util.regex.Pattern;

/** Maps one reviewed scalar DomainEvent payload path into a browser-safe Audit summary field. */
public record AuditPayloadFieldMapping(
        String summaryField, String sourcePath, boolean required) {

    private static final Pattern SUMMARY_FIELD = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Pattern SOURCE_PATH =
            Pattern.compile("[a-z][A-Za-z0-9]{0,63}(\\.[a-z][A-Za-z0-9]{0,63}){0,3}");

    public AuditPayloadFieldMapping {
        summaryField = require(summaryField, SUMMARY_FIELD, "summaryField");
        sourcePath = require(sourcePath, SOURCE_PATH, "sourcePath");
    }

    private static String require(String value, Pattern pattern, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a bounded payload field path");
        }
        return normalized;
    }
}
