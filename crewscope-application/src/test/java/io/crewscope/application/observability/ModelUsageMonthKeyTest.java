package io.crewscope.application.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Proves the reporting-month projection (M10-F03): zone-driven grouping and format. */
class ModelUsageMonthKeyTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void groupsByTheConfiguredZoneNotTheFact() {
        UtcTimestamp instant = UtcTimestamp.parse("2026-10-31T17:30:00Z");

        assertEquals("2026-10", ModelUsageMonthKey.of(instant, java.time.ZoneOffset.UTC).value());
        // The same instant is already November 1st in Shanghai — the boundary month.
        assertEquals("2026-11", ModelUsageMonthKey.of(instant, SHANGHAI).value());
    }

    @Test
    void acceptsEveryLegalMonthAndRejectsEverythingElse() {
        assertEquals("2026-01", new ModelUsageMonthKey("2026-01").value());
        assertEquals("2026-12", new ModelUsageMonthKey("2026-12").value());

        for (String illegal : new String[] {"2026-00", "2026-13", "2026-1", "26-01",
                "2026/01", "20261", "", "2026-01x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ModelUsageMonthKey(illegal), "expected rejection: " + illegal);
        }
    }
}
