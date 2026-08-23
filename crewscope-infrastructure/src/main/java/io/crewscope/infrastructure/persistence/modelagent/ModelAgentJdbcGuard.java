package io.crewscope.infrastructure.persistence.modelagent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Shared validation and PostgreSQL transaction-lock primitives for M5 append streams. */
final class ModelAgentJdbcGuard {

    private ModelAgentJdbcGuard() {}

    static void lock(NamedParameterJdbcTemplate jdbc, String streamKey) {
        Objects.requireNonNull(jdbc, "jdbc").queryForObject(
                "SELECT 1::BIGINT FROM pg_advisory_xact_lock(hashtextextended(:streamKey, 0))",
                new MapSqlParameterSource("streamKey", Objects.requireNonNull(streamKey)),
                Long.class);
    }

    static void requireNextRevision(
            String field, long requested, Long committedLatest) {
        long expected = committedLatest == null ? 1 : committedLatest + 1;
        if (requested != expected) {
            throw new DomainValidationException(
                    field, "must append revision " + expected + " after the committed latest row");
        }
    }

    static void requirePage(int offset, int limit, String field) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new DomainValidationException(
                    field, "offset must be non-negative and limit between 1 and 200");
        }
    }
}
