package io.crewscope.server.observability;

import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefenseTelemetry;
import io.crewscope.server.security.login.LoginDefenseMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Freezes authentication metrics to enum-only coordinates with a 64-series upper bound. */
public final class AuthenticationMetricPolicy {

    public static final int MAXIMUM_TOTAL_SERIES = AuthenticationFlow.values().length
            * LoginDefenseTelemetry.Operation.values().length
            * LoginDefenseTelemetry.Outcome.values().length;

    private static final Set<String> LABELS = Set.of("flow", "operation", "outcome");
    private static final Map<String, Set<String>> VALUES = Map.of(
            "flow", enumValues(AuthenticationFlow.values()),
            "operation", enumValues(LoginDefenseTelemetry.Operation.values()),
            "outcome", enumValues(LoginDefenseTelemetry.Outcome.values()));

    /** Denies undeclared authentication meters, coordinates and identity-bearing labels. */
    public MeterFilter meterFilter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if (!id.getName().startsWith("crewscope.authentication.")) {
                    return MeterFilterReply.NEUTRAL;
                }
                return accepts(id) ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY;
            }
        };
    }

    private static boolean accepts(Meter.Id id) {
        if (!LoginDefenseMetrics.OPERATIONS.equals(id.getName())) {
            return false;
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (Tag tag : id.getTags()) {
            if (actual.put(tag.getKey(), tag.getValue()) != null) {
                return false;
            }
        }
        if (!actual.keySet().equals(LABELS)) {
            return false;
        }
        return actual.entrySet().stream().allMatch(entry -> VALUES
                .getOrDefault(entry.getKey(), Set.of())
                .contains(entry.getValue()));
    }

    private static Set<String> enumValues(Enum<?>[] values) {
        return java.util.Arrays.stream(values)
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
