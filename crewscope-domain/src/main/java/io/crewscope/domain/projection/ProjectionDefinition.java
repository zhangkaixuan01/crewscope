package io.crewscope.domain.projection;

import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Objects;
import java.util.regex.Pattern;

/** Versioned executable contract used to build and validate one projection. */
public record ProjectionDefinition(
        ProjectionName name,
        ProjectionDefinitionVersion version,
        SchemaVersion projectionSchemaVersion,
        String canonicalEncoder,
        String validator) {

    public static final int MAX_COMPONENT_LENGTH = 160;
    private static final Pattern COMPONENT =
            Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public ProjectionDefinition {
        name = Objects.requireNonNull(name, "name");
        version = Objects.requireNonNull(version, "version");
        projectionSchemaVersion = Objects.requireNonNull(
                projectionSchemaVersion, "projectionSchemaVersion");
        canonicalEncoder = requireComponent(canonicalEncoder, "canonicalEncoder");
        validator = requireComponent(validator, "validator");
    }

    private static String requireComponent(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String candidate = value.strip();
        if (candidate.length() > MAX_COMPONENT_LENGTH || !COMPONENT.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a stable lower-case component coordinate");
        }
        return candidate;
    }
}
