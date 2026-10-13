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
        return windowOf(
                ModelUsageMonthKey.of(occurredAt, reportingZone).value(), reportingZone);
    }

    /** Projects an already-validated month key into its [start, end) UTC instants. */
    public static MonthWindow windowOf(String usageMonth, ZoneId reportingZone) {
        Objects.requireNonNull(reportingZone, "reportingZone");
        YearMonth month = YearMonth.parse(ModelUsageMonthKey.formatChecked(usageMonth));
        return new MonthWindow(
                month.toString(),
                month.atDay(1).atStartOfDay(reportingZone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(reportingZone).toInstant());
    }
}
