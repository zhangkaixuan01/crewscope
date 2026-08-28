package io.crewscope.server.security.login;

import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.LoginDefenseTelemetry;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/** Conditional M7 login-defense assembly; enabled deployments must supply an external HMAC key. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "crewscope.security.login-defense.enabled",
        havingValue = "true")
@EnableConfigurationProperties(LoginDefenseProperties.class)
public class LoginDefenseConfiguration {

    @Bean
    LoginDefenseResourceHasher loginDefenseResourceHasher(LoginDefenseProperties properties) {
        return new LoginDefenseResourceHasher(
                properties.getHmacKeyId(), properties.getHmacKey());
    }

    @Bean
    LoginDefenseKeyspace loginDefenseKeyspace(LoginDefenseProperties properties) {
        return new LoginDefenseKeyspace(properties.getEnvironment());
    }

    @Bean
    ControlledNetworkSourceResolver controlledNetworkSourceResolver(
            LoginDefenseProperties properties) {
        return new ControlledNetworkSourceResolver(properties.getTrustedProxies());
    }

    @Bean
    @ConditionalOnMissingBean(LoginDefenseTelemetry.class)
    LoginDefenseTelemetry loginDefenseTelemetry(MeterRegistry registry) {
        return new LoginDefenseMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock loginDefenseClock() {
        return Clock.systemUTC();
    }

    @Bean
    LoginDefense loginDefense(
            ReactiveStringRedisTemplate redis,
            LoginDefenseResourceHasher hasher,
            LoginDefenseKeyspace keyspace,
            Clock clock,
            LoginDefenseTelemetry telemetry) {
        return new RedisLoginDefense(
                redis, hasher, keyspace, LoginAttemptPolicy.standard(), clock, telemetry);
    }
}
