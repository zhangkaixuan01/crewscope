package io.crewscope.application.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The month boundary must follow the reporting zone, not the stored UTC instant: the intake
 * COUNT window, the ledger month key and the usage default month all cut on this edge.
 */
class QuotaMonthCalendarTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void projectsAUtcInstantIntoUtcMonthHalves() {
        QuotaMonthCalendar.MonthWindow window =
                QuotaMonthCalendar.monthOf(UtcTimestamp.parse("2026-10-13T00:30:00Z"), UTC);

        assertEquals("2026-10", window.usageMonth());
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), window.endExclusive());
    }

    @Test
    void lateUtcStillCountsAsThePreviousShanghaiDayOnlyUpToTheZoneEdge() {
        // 20:00 UTC on Sep 30 is Oct 1 04:00 in Shanghai: Shanghai reports October, UTC September.
        QuotaMonthCalendar.MonthWindow shanghai =
                QuotaMonthCalendar.monthOf(UtcTimestamp.parse("2026-09-30T20:00:00Z"), SHANGHAI);
        QuotaMonthCalendar.MonthWindow utc =
                QuotaMonthCalendar.monthOf(UtcTimestamp.parse("2026-09-30T20:00:00Z"), UTC);

        assertEquals("2026-10", shanghai.usageMonth());
        assertEquals("2026-09", utc.usageMonth());
    }

    @Test
    void rollsTheYearBoundaryThroughTheZone() {
        QuotaMonthCalendar.MonthWindow window =
                QuotaMonthCalendar.monthOf(UtcTimestamp.parse("2026-12-31T23:00:00Z"), SHANGHAI);

        assertEquals("2027-01", window.usageMonth());
        assertEquals(Instant.parse("2026-12-31T16:00:00Z"), window.startInclusive());
        assertEquals(Instant.parse("2027-01-31T16:00:00Z"), window.endExclusive());
    }

    @Test
    void windowOfProjectsAValidatedMonthKeyIntoTheSameEdges() {
        QuotaMonthCalendar.MonthWindow fromKey =
                QuotaMonthCalendar.windowOf("2026-10", SHANGHAI);
        QuotaMonthCalendar.MonthWindow fromInstant =
                QuotaMonthCalendar.monthOf(UtcTimestamp.parse("2026-10-13T01:00:00Z"), SHANGHAI);

        assertEquals(fromInstant, fromKey);
        assertEquals(Instant.parse("2026-09-30T16:00:00Z"), fromKey.startInclusive());
        assertEquals(Instant.parse("2026-10-31T16:00:00Z"), fromKey.endExclusive());
    }

    @Test
    void windowOfRejectsAMalformedKey() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> QuotaMonthCalendar.windowOf("2026-1", UTC));
    }
}
