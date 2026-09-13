package io.crewscope.application.setup;

import java.util.Objects;

/** Safe search hit for a configuration field; values and prompts are never returned. */
public record ConfigurationSearchResult(
        String profileId,
        long revision,
        String field,
        String label,
        String route) {

    public ConfigurationSearchResult {
        profileId = requireText(profileId, "profileId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        field = requireText(field, "field");
        label = requireText(label, "label");
        route = requireText(route, "route");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.strip();
    }
}
