package io.crewscope.application.quota;

import io.crewscope.application.observability.ModelUsageMonthKey;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Single source for the quota month boundary: the {@code YYYY-MM} key, the window the intake
 * COUNT reads and the month the token ledger keys on all derive from here, projected into the
 * same reporting zone the usage rollup groups by (never inferred per fact).
 */
public final class QuotaMonthCalendar {

    private QuotaMonthCalendar() {
    }

    /** The reporting month key plus its [start, end) UTC instants. */
    public record MonthWindow(String usageMonth, Instant startInclusive, Instant endExclusive) {
    }

    public static MonthWindow monthOf(UtcTimestamp occurredAt, ZoneId reportingZone) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(reportingZone, "reportingZone");
        String usageMonth = ModelUsageMonthKey.of(occurredAt, reportingZone).value();
        YearMonth month = YearMonth.parse(usageMonth);
        return new MonthWindow(
                usageMonth,
                month.atDay(1).atStartOfDay(reportingZone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(reportingZone).toInstant());
    }
}
