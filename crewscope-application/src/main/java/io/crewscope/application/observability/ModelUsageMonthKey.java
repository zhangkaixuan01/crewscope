package io.crewscope.application.observability;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The reporting month a usage fact groups into (M10-F03, contract §"month boundary"): the
 * fact's {@code occurredAt} instant projected into the configured reporting zone, then
 * formatted as {@code YYYY-MM}. The zone is an assembly-level decision (defaults to UTC,
 * deployments pin Asia/Shanghai) — never inferred from the fact itself.
 */
public record ModelUsageMonthKey(String value) {

    static final Pattern FORMAT = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

    private static final DateTimeFormatter MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM");

    public ModelUsageMonthKey {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "usage month must be formatted YYYY-MM, got: " + value);
        }
    }

    public static ModelUsageMonthKey of(UtcTimestamp occurredAt, ZoneId reportingZone) {
        return new ModelUsageMonthKey(MONTH_FORMATTER.format(
                Objects.requireNonNull(occurredAt, "occurredAt")
                        .value()
                        .atZone(Objects.requireNonNull(reportingZone, "reportingZone"))));
    }

    /** Rejects a raw request month through the same format invariant, returning it verbatim. */
    public static String formatChecked(String value) {
        return new ModelUsageMonthKey(value).value();
    }

    @Override
    public String toString() {
        return value;
    }
}
