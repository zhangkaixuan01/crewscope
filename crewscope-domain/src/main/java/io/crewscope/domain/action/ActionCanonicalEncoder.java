package io.crewscope.domain.action;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Versioned length-prefixed encoder that prevents delimiter and map-order ambiguity. */
final class ActionCanonicalEncoder {

    private final StringBuilder canonical;

    ActionCanonicalEncoder(String schema) {
        canonical = new StringBuilder(Objects.requireNonNull(schema, "schema"));
    }

    ActionCanonicalEncoder add(String value) {
        String required = Objects.requireNonNull(value, "canonicalValue");
        canonical.append('|').append(required.length()).append(':').append(required);
        return this;
    }

    TaskFactHash digest() {
        return TaskFactHash.sha256(canonical.toString());
    }
}
