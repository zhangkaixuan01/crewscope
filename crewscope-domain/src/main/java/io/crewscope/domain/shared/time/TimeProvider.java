package io.crewscope.domain.shared.time;

import java.time.Clock;
import java.util.Objects;

/** Injectable source of normalized business time for deterministic domain and application tests. */
@FunctionalInterface
public interface TimeProvider {

    UtcTimestamp now();

    /** Adapts a JDK Clock while retaining CrewScope timestamp normalization. */
    static TimeProvider from(Clock clock) {
        Clock requiredClock = Objects.requireNonNull(clock, "clock");
        return () -> UtcTimestamp.from(requiredClock.instant());
    }
}
