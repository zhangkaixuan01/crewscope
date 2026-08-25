package io.crewscope.domain.activity;

import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identity of one explicitly reviewed public Activity payload schema. */
public record ActivityPayloadSchemaRef(String name, SchemaVersion version) {

    public static final int MAX_NAME_LENGTH = 120;
    private static final Pattern NAME_FORMAT =
            Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public ActivityPayloadSchemaRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ActivityPayloadSchemaRef.name must not be blank");
        }
        name = name.strip();
        if (name.length() > MAX_NAME_LENGTH || !NAME_FORMAT.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Activity payload schema name must use lower-case dot or kebab segments");
        }
        version = Objects.requireNonNull(version, "version");
    }
}
