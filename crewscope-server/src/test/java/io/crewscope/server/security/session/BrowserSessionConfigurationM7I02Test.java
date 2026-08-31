package io.crewscope.server.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.domain.identity.SecurityVersion;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;

/** Unit contract for the M7-I02 configuration guard and Session serialization allowlist. */
class BrowserSessionConfigurationM7I02Test {

    private final BrowserSessionConfiguration configuration =
            new BrowserSessionConfiguration();

    @Test
    void acceptsOnlyTheBoot4IndexedNamespaceContract() {
        BrowserSessionProperties properties = properties();
        MockEnvironment environment = environment(properties);

        BrowserSessionConfiguration.validateConfiguration(environment, properties);

        environment.setProperty("spring.session.redis.namespace", "legacy");
        assertThatThrownBy(() -> BrowserSessionConfiguration.validateConfiguration(
                        environment, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy spring.session.redis.namespace");
    }

    @Test
    void rejectsSilentFallbacksAndUnboundedPolicyValues() {
        BrowserSessionProperties properties = properties();
        MockEnvironment environment = environment(properties);
        environment.setProperty("spring.session.data.redis.repository-type", "default");

        assertThatThrownBy(() -> BrowserSessionConfiguration.validateConfiguration(
                        environment, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repository-type");

        properties.setMaximumSessions(21);
        assertThatThrownBy(() -> BrowserSessionConfiguration.validateConfiguration(
                        environment(properties), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 1 and 20");

        BrowserSessionProperties tooShort = properties();
        tooShort.setTtl(java.time.Duration.ofNanos(1));
        assertThatThrownBy(() -> BrowserSessionConfiguration.validateConfiguration(
                        environment(tooShort), tooShort))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 1 second and 7 days");
    }

    @Test
    void roundTripsOnlyTheCredentialFreeBrowserSecurityContext() {
        RedisSerializer<Object> serializer = configuration.springSessionDefaultRedisSerializer();
        BrowserSessionPrincipal principal =
                new BrowserSessionPrincipal(UUID.randomUUID(), 7);
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextImpl context = new SecurityContextImpl(authentication);

        byte[] encoded = serializer.serialize(context);
        Object decoded = serializer.deserialize(encoded);

        assertThat(decoded).isInstanceOf(SecurityContextImpl.class);
        SecurityContextImpl restored = (SecurityContextImpl) decoded;
        assertThat(restored.getAuthentication().getPrincipal()).isEqualTo(principal);
        assertThat(restored.getAuthentication().getCredentials()).isNull();
        String payload = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload)
                .contains("\"credentials\":null")
                .doesNotContain("example-password", "example-password-hash");

        byte[] unexpected = serializer.serialize(new SecurityVersion(1));
        assertThatThrownBy(() -> serializer.deserialize(unexpected))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void roundTripsTheIndexedSessionLifecycleEventShape() {
        RedisSerializer<Object> serializer = configuration.springSessionDefaultRedisSerializer();
        Map<String, Object> lifecycleEvent = new HashMap<>();
        lifecycleEvent.put("sessionId", UUID.randomUUID().toString());
        lifecycleEvent.put("lastAccessedTime", 1_799_000_000_000L);

        byte[] encoded = serializer.serialize(lifecycleEvent);
        Object decoded = serializer.deserialize(encoded);

        assertThat(decoded).isEqualTo(lifecycleEvent);
    }

    private static BrowserSessionProperties properties() {
        BrowserSessionProperties properties = new BrowserSessionProperties();
        properties.setTtl(java.time.Duration.ofHours(12));
        properties.setMaximumSessions(5);
        properties.setNamespace("crewscope:session");
        return properties;
    }

    private static MockEnvironment environment(BrowserSessionProperties properties) {
        return new MockEnvironment().withProperty(
                        "spring.session.timeout", properties.getTtl().toString())
                .withProperty(
                        "server.reactive.session.timeout", properties.getTtl().toString())
                .withProperty(
                        "spring.session.data.redis.namespace", properties.getNamespace())
                .withProperty("spring.session.data.redis.repository-type", "indexed")
                .withProperty("spring.session.data.redis.save-mode", "on-set-attribute");
    }
}
