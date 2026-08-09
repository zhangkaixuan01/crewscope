package io.crewscope.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Locks down configuration surfaces that could otherwise expose credential material. */
class CredentialActuatorConfigurationTest {

    @Test
    void exposesOnlySafeActuatorEndpointsAndNeverShowsConfigurationValues() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));

        assertEquals(
                "health,info,prometheus",
                property(sources, "management.endpoints.web.exposure.include"));
        assertEquals("never", property(sources, "management.endpoint.env.show-values"));
        assertEquals("never", property(sources, "management.endpoint.configprops.show-values"));
        assertEquals("never", property(sources, "management.endpoint.health.show-details"));

        String currentKeyId = property(
                sources, "crewscope.credential.encryption.current-key-id");
        String keys = property(sources, "crewscope.credential.encryption.keys");
        assertEquals("${CREWSCOPE_CREDENTIAL_CURRENT_KEY_ID:}", currentKeyId);
        assertEquals("${CREWSCOPE_CREDENTIAL_KEYS:}", keys);
        assertFalse(keys.matches(".*=[A-Za-z0-9+/]{43}=?.*"));

        assertEquals(
                "${CREWSCOPE_REDIS_URL:redis://localhost:6379}",
                property(sources, "crewscope.runtime.redis.url"));
        assertEquals(
                "${CREWSCOPE_ENVIRONMENT:development}",
                property(sources, "crewscope.runtime.redis.environment"));
        assertEquals(
                "${CREWSCOPE_AGENT_EXECUTION_OWNERSHIP_LEASE:30s}",
                property(sources, "crewscope.runtime.redis.ownership-lease"));
        assertEquals(
                "${CREWSCOPE_AGENT_EXECUTION_OWNERSHIP_RENEWAL:5s}",
                property(sources, "crewscope.runtime.redis.ownership-renewal"));
    }

    private static String property(List<PropertySource<?>> sources, String name) {
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .map(Object::toString)
                .collect(Collectors.joining());
    }
}
