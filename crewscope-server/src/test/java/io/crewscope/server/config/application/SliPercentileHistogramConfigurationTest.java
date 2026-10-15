package io.crewscope.server.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Locks the exact bracket-key form of the per-meter histogram configuration (M12-I03,
 * application.yml): a wrongly shaped key binds silently — no error, no histogram — so the
 * property is proven by scraping the exposition text of the configured meters, plus one
 * unconfigured control meter that must stay bucket-free.
 */
class SliPercentileHistogramConfigurationTest {

    @Test
    void bracketKeyMeterNamesEnablePercentileHistograms() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class))
                .withPropertyValues(
                        "management.metrics.distribution.percentiles-histogram"
                                + ".[crewscope.api.requests]=true",
                        "management.metrics.distribution.percentiles-histogram"
                                + ".[crewscope.m6.outbox.duration]=true",
                        "management.metrics.distribution.percentiles-histogram"
                                + ".[crewscope.m6.projection.duration]=true",
                        "management.metrics.distribution.percentiles-histogram"
                                + ".[crewscope.m10.knowledge.retrieval.duration]=true")
                .run(context -> {
                    PrometheusMeterRegistry registry =
                            context.getBean(PrometheusMeterRegistry.class);
                    for (String meter : new String[] {
                        "crewscope.api.requests",
                        "crewscope.m6.outbox.duration",
                        "crewscope.m6.projection.duration",
                        "crewscope.m10.knowledge.retrieval.duration"
                    }) {
                        Timer.builder(meter)
                                .tag("probe", "histogram")
                                .register(registry)
                                .record(Duration.ofMillis(120));
                    }
                    Timer.builder("crewscope.sli.unconfigured.control")
                            .tag("probe", "histogram")
                            .register(registry)
                            .record(Duration.ofMillis(120));

                    String scrape = registry.scrape();
                    assertThat(scrape)
                            .contains("crewscope_api_requests_seconds_bucket")
                            .contains("crewscope_m6_outbox_duration_seconds_bucket")
                            .contains("crewscope_m6_projection_duration_seconds_bucket")
                            .contains("crewscope_m10_knowledge_retrieval_duration_seconds_bucket")
                            .doesNotContain("crewscope_sli_unconfigured_control_seconds_bucket");
                });
    }
}
