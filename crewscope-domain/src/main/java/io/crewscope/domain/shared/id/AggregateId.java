package io.crewscope.domain.shared.id;

import java.util.Objects;
import java.util.UUID;

/** Common contract for strongly typed UUID aggregate identifiers. */
public interface AggregateId {

    UUID NIL_UUID = new UUID(0L, 0L);

    /** Returns the persistence representation shared by PostgreSQL and external contracts. */
    UUID value();

    /** Validates a UUID supplied to a concrete identifier record. */
    static UUID requireValue(UUID value, String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        UUID required = Objects.requireNonNull(value, typeName + ".value");
        if (NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(typeName + " must not use the nil UUID");
        }
        return required;
    }

    /** Parses only canonical UUID text so API and event identifiers have one representation. */
    static UUID parseCanonical(String value, String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(typeName + " must not be blank");
        }
        String candidate = value.strip();
        UUID parsed;
        try {
            parsed = UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(typeName + " must be a canonical UUID", exception);
        }
        if (!parsed.toString().equalsIgnoreCase(candidate)) {
            throw new IllegalArgumentException(typeName + " must be a canonical UUID");
        }
        return requireValue(parsed, typeName);
    }

    /** Generates a non-nil UUID using the JDK cryptographically strong random source. */
    static UUID generateValue() {
        return UUID.randomUUID();
    }
}
