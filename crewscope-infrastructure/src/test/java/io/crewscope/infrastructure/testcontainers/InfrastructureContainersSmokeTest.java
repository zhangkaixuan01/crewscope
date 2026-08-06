package io.crewscope.infrastructure.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** M0-D01 smoke tests proving that PostgreSQL and Redis need no local service. */
@Tag("integration")
@SpringJUnitConfig(InfrastructureContainersSmokeTest.TestConfiguration.class)
class InfrastructureContainersSmokeTest extends AbstractPostgresRedisContainerIntegrationTest {

    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(5);

    @Autowired private Environment environment;

    @Test
    void dynamicPropertiesPointOnlyToContainerEndpoints() {
        assertEquals(POSTGRES.getJdbcUrl(), requiredProperty("spring.datasource.url"));
        assertEquals(POSTGRES.getUsername(), requiredProperty("spring.datasource.username"));
        assertEquals(POSTGRES.getPassword(), requiredProperty("spring.datasource.password"));
        assertEquals(REDIS.getHost(), requiredProperty("spring.data.redis.host"));
        assertEquals(
                REDIS.getFirstMappedPort(),
                environment.getRequiredProperty("spring.data.redis.port", Integer.class));

        // Random mapped ports prove that localhost:5432/6379 from development config are unused.
        assertNotEquals(5432, POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        assertNotEquals(REDIS_PORT, REDIS.getFirstMappedPort());
    }

    @Test
    void postgresAcceptsJdbcConnectionFromDynamicProperties() throws Exception {
        try (var connection =
                        DriverManager.getConnection(
                                requiredProperty("spring.datasource.url"),
                                requiredProperty("spring.datasource.username"),
                                requiredProperty("spring.datasource.password"));
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "select current_database(), current_user, version()")) {
            assertTrue(result.next());
            assertEquals("crewscope", result.getString(1));
            assertEquals("crewscope", result.getString(2));
            assertTrue(result.getString(3).contains("PostgreSQL 17"));
        }
    }

    @Test
    void redisAcceptsRespPingFromDynamicProperties() throws Exception {
        String host = requiredProperty("spring.data.redis.host");
        int port = environment.getRequiredProperty("spring.data.redis.port", Integer.class);

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout((int) SOCKET_TIMEOUT.toMillis());
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals("+PONG", reader.readLine());
            }
        }
    }

    private String requiredProperty(String name) {
        return environment.getRequiredProperty(name);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {}
}
