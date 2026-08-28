package io.crewscope.server.security.session;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.session.ReactiveSessionRegistry;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.web.server.authentication.ConcurrentSessionControlServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.RegisterSessionServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.session.SaveMode;
import org.springframework.session.config.ReactiveSessionRepositoryCustomizer;
import org.springframework.session.data.redis.ReactiveRedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.server.EnableRedisIndexedWebSession;
import org.springframework.session.security.SpringSessionBackedReactiveSessionRegistry;
import org.springframework.session.web.server.session.SpringSessionWebSessionStore;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/** Production Spring Session Redis assembly and fail-closed configuration guards. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "crewscope.security.session.enabled",
        havingValue = "true")
@EnableRedisIndexedWebSession(redisNamespace = "${crewscope.security.session.namespace}")
@EnableConfigurationProperties(BrowserSessionProperties.class)
public class BrowserSessionConfiguration {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9:_-]{2,99}");

    @Bean
    WebSessionServerSecurityContextRepository browserSecurityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    ReactiveSessionRepositoryCustomizer<ReactiveRedisIndexedSessionRepository>
            browserSessionRepositoryCustomizer(BrowserSessionProperties properties) {
        return repository -> {
            repository.setDefaultMaxInactiveInterval(properties.getTtl());
            repository.setRedisKeyNamespace(properties.getNamespace());
            repository.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
        };
    }

    @Bean
    ReactiveSessionRegistry browserReactiveSessionRegistry(
            ReactiveRedisIndexedSessionRepository sessions) {
        return new SpringSessionBackedReactiveSessionRegistry<>(sessions, sessions);
    }

    @Bean
    ConcurrentSessionControlServerAuthenticationSuccessHandler browserConcurrentSessions(
            ReactiveSessionRegistry registry,
            ReactiveRedisIndexedSessionRepository sessions,
            BrowserSessionProperties properties) {
        return BrowserSessionLifecycle.concurrentSessions(
                registry,
                new SpringSessionWebSessionStore<>(sessions),
                properties.getMaximumSessions());
    }

    @Bean
    RegisterSessionServerAuthenticationSuccessHandler browserSessionRegistration(
            ReactiveSessionRegistry registry) {
        return new RegisterSessionServerAuthenticationSuccessHandler(registry);
    }

    @Bean
    BrowserSessionLifecycle browserSessionLifecycle(
            WebSessionServerSecurityContextRepository securityContexts,
            ConcurrentSessionControlServerAuthenticationSuccessHandler concurrentSessions,
            RegisterSessionServerAuthenticationSuccessHandler sessionRegistration) {
        return new BrowserSessionLifecycle(
                securityContexts, concurrentSessions, sessionRegistration);
    }

    /** Spring Session discovers this exact bean name when constructing its Redis template. */
    @Bean("springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        ClassLoader classLoader = BrowserSessionConfiguration.class.getClassLoader();
        BasicPolymorphicTypeValidator.Builder whitelist =
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("org.springframework.security.")
                        .allowIfSubType("io.crewscope.server.security.session.")
                        .allowIfSubTypeIsArray();
        List<JacksonModule> securityModules =
                SecurityJacksonModules.getModules(classLoader, whitelist);
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(whitelist.build())
                .customize(mapper -> mapper.addModules(securityModules))
                .build();
    }

    @Bean
    InitializingBean browserSessionConfigurationGuard(
            Environment environment, BrowserSessionProperties properties) {
        return () -> validateConfiguration(environment, properties);
    }

    static void validateConfiguration(
            Environment environment, BrowserSessionProperties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        Duration ttl = Objects.requireNonNull(properties.getTtl(), "session ttl");
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0
                || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalStateException("browser Session ttl must be between 1 second and 7 days");
        }
        if (properties.getMaximumSessions() < 1 || properties.getMaximumSessions() > 20) {
            throw new IllegalStateException("maximum browser Sessions must be between 1 and 20");
        }
        String namespace = Objects.requireNonNull(properties.getNamespace(), "session namespace")
                .strip();
        if (!namespace.equals(namespace.toLowerCase(Locale.ROOT))
                || !NAMESPACE.matcher(namespace).matches()
                || "spring:session".equals(namespace)) {
            throw new IllegalStateException("browser Session namespace is invalid or unscoped");
        }
        String configuredSpringSessionTtl = environment.getProperty("spring.session.timeout");
        Duration springSessionTtl = configuredSpringSessionTtl == null
                ? null
                : DurationStyle.detectAndParse(configuredSpringSessionTtl.strip());
        if (!ttl.equals(springSessionTtl)) {
            throw new IllegalStateException("spring.session.timeout must match browser Session ttl");
        }
        String configuredWebSessionTtl =
                environment.getProperty("server.reactive.session.timeout");
        Duration webSessionTtl = configuredWebSessionTtl == null
                ? null
                : DurationStyle.detectAndParse(configuredWebSessionTtl.strip());
        if (!ttl.equals(webSessionTtl)) {
            throw new IllegalStateException(
                    "server.reactive.session.timeout must match browser Session ttl");
        }
        requireProperty(environment, "spring.session.data.redis.namespace", namespace);
        requireProperty(environment, "spring.session.data.redis.repository-type", "indexed");
        requireProperty(environment, "spring.session.data.redis.save-mode", "on-set-attribute");
        if (environment.containsProperty("spring.session.redis.namespace")) {
            throw new IllegalStateException(
                    "legacy spring.session.redis.namespace is forbidden; use Boot 4 data.redis");
        }
    }

    private static void requireProperty(
            Environment environment, String name, String expected) {
        String actual = environment.getProperty(name);
        if (actual == null || !actual.strip().equalsIgnoreCase(expected)) {
            throw new IllegalStateException(name + " must be " + expected);
        }
    }
}
