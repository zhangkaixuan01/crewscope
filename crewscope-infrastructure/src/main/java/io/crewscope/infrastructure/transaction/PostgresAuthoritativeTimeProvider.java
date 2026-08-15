package io.crewscope.infrastructure.transaction;

import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads PostgreSQL wall-clock time so Worker clock skew and lock waits cannot move boundaries. */
@Component
public class PostgresAuthoritativeTimeProvider implements AuthoritativeTimeProvider {

    private final JdbcTemplate jdbc;

    public PostgresAuthoritativeTimeProvider(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public UtcTimestamp now() {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT clock_timestamp()", OffsetDateTime.class);
        return UtcTimestamp.from(Objects.requireNonNull(value, "PostgreSQL wall-clock time"));
    }
}
