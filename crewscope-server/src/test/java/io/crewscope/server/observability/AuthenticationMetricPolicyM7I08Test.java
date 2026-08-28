package io.crewscope.server.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.LoginDefenseTelemetry;
import io.crewscope.server.security.login.LoginDefenseMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Locks the authentication registry to enum values and rejects identity-bearing dimensions. */
class AuthenticationMetricPolicyM7I08Test {

    @Test
    void acceptsTheFixedDefenseCounterWithinA64SeriesBudget() {
        SimpleMeterRegistry registry = registry();
        LoginDefenseMetrics metrics = new LoginDefenseMetrics(registry);

        metrics.record(
                AuthenticationFlow.LOGIN,
                LoginDefenseTelemetry.Operation.RESOURCE_ADMISSION,
                LoginDefenseTelemetry.Outcome.ALLOWED);

        assertThat(AuthenticationMetricPolicy.MAXIMUM_TOTAL_SERIES).isEqualTo(64);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void deniesUnknownValuesMetricsAndIdentityBearingLabels() {
        for (String dynamicLabel : List.of(
                "username", "email", "networkAddress", "sessionId", "redisKey")) {
            SimpleMeterRegistry registry = registry();
            Counter.builder(LoginDefenseMetrics.OPERATIONS)
                    .tags(
                            "flow", "login",
                            "operation", "resource_admission",
                            "outcome", "allowed",
                            dynamicLabel, "member-specific-value")
                    .register(registry)
                    .increment();
            assertThat(registry.getMeters()).as(dynamicLabel).isEmpty();
        }

        SimpleMeterRegistry invalidValue = registry();
        Counter.builder(LoginDefenseMetrics.OPERATIONS)
                .tags("flow", "member-42", "operation", "resource_admission", "outcome", "allowed")
                .register(invalidValue)
                .increment();
        Counter.builder("crewscope.authentication.password.attempts")
                .tags("flow", "login", "operation", "resource_admission", "outcome", "allowed")
                .register(invalidValue)
                .increment();
        assertThat(invalidValue.getMeters()).isEmpty();
    }

    private static SimpleMeterRegistry registry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new AuthenticationMetricPolicy().meterFilter());
        return registry;
    }
}
