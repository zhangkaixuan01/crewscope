package io.crewscope.domain.shared.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UtcTimestampTest {

    @Test
    void normalizesJvmNanosecondsToPostgreSqlMicroseconds() {
        UtcTimestamp timestamp = UtcTimestamp.parse("2026-08-06T12:34:56.123456789Z");

        assertEquals(Instant.parse("2026-08-06T12:34:56.123456Z"), timestamp.value());
        assertEquals("2026-08-06T12:34:56.123456Z", timestamp.toString());
    }

    @Test
    void convertsOffsetTimeToUtc() {
        UtcTimestamp timestamp = UtcTimestamp.from(
                OffsetDateTime.parse("2026-08-06T20:34:56.123456789+08:00"));

        assertEquals(
                OffsetDateTime.parse("2026-08-06T12:34:56.123456Z"),
                timestamp.toOffsetDateTime());
        assertEquals(ZoneOffset.UTC, timestamp.toOffsetDateTime().getOffset());
    }

    @Test
    void timeProviderAdaptsAFixedClockForDeterministicTests() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-06T12:34:56.123456789Z"), ZoneOffset.UTC);

        assertEquals(
                UtcTimestamp.parse("2026-08-06T12:34:56.123456Z"),
                TimeProvider.from(clock).now());
    }

    @Test
    void rejectsMissingTimestampValues() {
        assertThrows(NullPointerException.class, () -> UtcTimestamp.from((Instant) null));
        assertThrows(NullPointerException.class, () -> UtcTimestamp.from((OffsetDateTime) null));
        assertThrows(IllegalArgumentException.class, () -> UtcTimestamp.parse("  "));
    }
}
