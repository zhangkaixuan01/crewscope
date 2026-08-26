package io.crewscope.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Locks down the Reactor, W3C tracing and structured logging configuration contract. */
class ApiObservabilityConfigurationTest {

    @Test
    void configuresAutomaticContextW3cTracingAndJsonLogsWithoutDefaultExport() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertEquals("auto", property(sources, "spring.reactor.context-propagation"));
        assertEquals("w3c", property(sources, "management.tracing.propagation.type"));
        assertEquals(
                "${CREWSCOPE_TRACE_SAMPLING_PROBABILITY:1.0}",
                property(sources, "management.tracing.sampling.probability"));
        assertEquals(
                "${CREWSCOPE_OTLP_TRACING_ENABLED:false}",
                property(sources, "management.tracing.export.otlp.enabled"));
        assertEquals(
                "${CREWSCOPE_LOG_FORMAT:logstash}",
                property(sources, "logging.structured.format.console"));
        assertEquals("true", property(sources, "logging.structured.json.context.include"));
        assertEquals(
                SafeStructuredLoggingJsonCustomizer.class.getName(),
                property(sources, "logging.structured.json.customizer"));
    }

    private static String property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .map(Object::toString)
                .collect(Collectors.joining());
    }
}
