package io.crewscope.infrastructure.persistence.coding;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Converts domain values to explicit JDBC-compatible representations. */
final class CodingJdbcValue {

    private CodingJdbcValue() {}

    /** PostgreSQL stores TIMESTAMPTZ values through OffsetDateTime without driver type guessing. */
    static OffsetDateTime timestamp(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(value).value(), ZoneOffset.UTC);
    }
}
