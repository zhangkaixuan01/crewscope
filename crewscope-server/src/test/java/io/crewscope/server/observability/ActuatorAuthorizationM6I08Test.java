package io.crewscope.server.observability;

import io.crewscope.server.config.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Proves that public probes remain safe while Prometheus requires operator authentication. */
@SpringBootTest(
        classes = ActuatorAuthorizationM6I08Test.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "crewscope.security.mode=bootstrap",
            "crewscope.security.bootstrap.username=operator",
            "crewscope.security.bootstrap.password=operator-password",
            "crewscope.security.monitoring.username=prometheus",
            "crewscope.security.monitoring.password=monitoring-password",
            "management.endpoints.web.exposure.include=health,info,prometheus",
            "management.endpoint.health.show-details=never",
            "management.endpoint.health.probes.enabled=true",
            // This slice verifies HTTP authorization, not external Redis availability.
            "management.health.redis.enabled=false",
            "management.tracing.export.otlp.enabled=false"
        })
class ActuatorAuthorizationM6I08Test {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void permitsSafeHealthAndRestrictsPrometheusToTheMonitoringCredential() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist("Set-Cookie")
                .expectBody(String.class)
                .value(body -> org.junit.jupiter.api.Assertions.assertFalse(
                        body.contains("dropped")));

        client.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");

        client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isUnauthorized();

        client.get().uri("/actuator/prometheus")
                .headers(headers -> headers.setBasicAuth("operator", "operator-password"))
                .exchange()
                .expectStatus()
                .isUnauthorized();

        client.get().uri("/actuator/prometheus")
                .headers(headers -> headers.setBasicAuth("prometheus", "monitoring-password"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist("Set-Cookie")
                .expectBody(String.class)
                .value(body -> org.junit.jupiter.api.Assertions.assertTrue(
                        body.contains("http_server_requests")));

        client.get().uri("/api/v1/protected-probe")
                .headers(headers -> headers.setBasicAuth("prometheus", "monitoring-password"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
            })
    @Import(SecurityConfiguration.class)
    static class TestApplication {}
}
