package io.crewscope.server.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring assembly for the M6 metric budget and aggregate telemetry health. */
@Configuration(proxyBeanMethods = false)
public class TeamBetaObservabilityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TeamBetaMetricPolicy teamBetaMetricPolicy() {
        return new TeamBetaMetricPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    AuthenticationMetricPolicy authenticationMetricPolicy() {
        return new AuthenticationMetricPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    TelemetryDegradationState telemetryDegradationState() {
        return new TelemetryDegradationState();
    }

    @Bean
    MeterRegistryCustomizer<MeterRegistry> m6MetricBudgetCustomizer(TeamBetaMetricPolicy policy) {
        MeterFilter filter = policy.meterFilter();
        return registry -> registry.config().meterFilter(filter);
    }

    @Bean
    MeterRegistryCustomizer<MeterRegistry> authenticationMetricBudgetCustomizer(
            AuthenticationMetricPolicy policy) {
        MeterFilter filter = policy.meterFilter();
        return registry -> registry.config().meterFilter(filter);
    }

    @Bean("crewscopeTelemetry")
    HealthIndicator crewscopeTelemetryHealthIndicator(TelemetryDegradationState state) {
        return () -> state.total() == 0
                ? Health.up().withDetail("dropped", state.snapshot()).build()
                : Health.status("DEGRADED")
                        .withDetail("dropped", state.snapshot())
                        .build();
    }
}
