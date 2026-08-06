package io.crewscope.domain.shared.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * UTC business timestamp normalized to PostgreSQL {@code TIMESTAMPTZ} microsecond precision.
 *
 * <p>Normalization prevents false changes when a nanosecond-precision JVM value completes a
 * database round trip through PostgreSQL.
 */
public record UtcTimestamp(Instant value) implements Comparable<UtcTimestamp> {

    public UtcTimestamp {
        value = Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MICROS);
    }

    public static UtcTimestamp from(Instant value) {
        return new UtcTimestamp(value);
    }

    public static UtcTimestamp from(OffsetDateTime value) {
        return new UtcTimestamp(Objects.requireNonNull(value, "value").toInstant());
    }

    public static UtcTimestamp parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("UtcTimestamp must not be blank");
        }
        return new UtcTimestamp(Instant.parse(value.strip()));
    }

    public OffsetDateTime toOffsetDateTime() {
        return value.atOffset(ZoneOffset.UTC);
    }

    @Override
    public int compareTo(UtcTimestamp other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
