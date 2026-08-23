package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Security and network bounds for the model credential lifecycle. */
@ConfigurationProperties(prefix = "crewscope.model.credential")
public class ModelCredentialProperties {

    private static final Duration MAXIMUM_HANDLE_TIME_TO_LIVE = Duration.ofMinutes(10);
    private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private Duration handleTtl = Duration.ofSeconds(30);
    private Duration healthConnectTimeout = Duration.ofSeconds(3);
    private Duration healthRequestTimeout = Duration.ofSeconds(10);

    public Duration getHandleTtl() {
        return handleTtl;
    }

    public void setHandleTtl(Duration handleTtl) {
        this.handleTtl = handleTtl;
    }

    public Duration getHealthConnectTimeout() {
        return healthConnectTimeout;
    }

    public void setHealthConnectTimeout(Duration healthConnectTimeout) {
        this.healthConnectTimeout = healthConnectTimeout;
    }

    public Duration getHealthRequestTimeout() {
        return healthRequestTimeout;
    }

    public void setHealthRequestTimeout(Duration healthRequestTimeout) {
        this.healthRequestTimeout = healthRequestTimeout;
    }

    public Duration validatedHandleTtl() {
        return requirePositiveAtMost(
                handleTtl,
                MAXIMUM_HANDLE_TIME_TO_LIVE,
                "crewscope.model.credential.handle-ttl");
    }

    public Duration validatedHealthConnectTimeout() {
        return requirePositiveAtMost(
                healthConnectTimeout,
                MAXIMUM_CONNECT_TIMEOUT,
                "crewscope.model.credential.health-connect-timeout");
    }

    public Duration validatedHealthRequestTimeout() {
        return requirePositiveAtMost(
                healthRequestTimeout,
                MAXIMUM_REQUEST_TIMEOUT,
                "crewscope.model.credential.health-request-timeout");
    }

    private static Duration requirePositiveAtMost(
            Duration value, Duration maximum, String propertyName) {
        Duration required = Objects.requireNonNull(value, propertyName);
        if (required.isZero() || required.isNegative() || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    propertyName + " must be positive and at most " + maximum);
        }
        return required;
    }
}
