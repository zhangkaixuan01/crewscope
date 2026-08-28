package io.crewscope.server.security.login;

import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefenseTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/** Low-cardinality authentication defense counter with enum-only coordinates. */
public final class LoginDefenseMetrics implements LoginDefenseTelemetry {

    public static final String OPERATIONS = "crewscope.authentication.defense.operations";

    private final MeterRegistry registry;

    public LoginDefenseMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void record(AuthenticationFlow flow, Operation operation, Outcome outcome) {
        LoginDefenseTelemetry.requireCoordinates(flow, operation, outcome);
        Counter.builder(OPERATIONS)
                .tags(
                        "flow", lower(flow),
                        "operation", lower(operation),
                        "outcome", lower(outcome))
                .register(registry)
                .increment();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
