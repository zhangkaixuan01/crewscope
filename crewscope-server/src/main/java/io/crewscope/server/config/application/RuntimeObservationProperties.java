package io.crewscope.server.config.application;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorker;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Read-side environment and freshness policy for M3 Runtime observation APIs. */
@ConfigurationProperties(prefix = "crewscope.runtime.observation")
public class RuntimeObservationProperties {

    private String environment = "development";
    private Duration heartbeatTimeout = Duration.ofSeconds(30);

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public Duration getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(Duration heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public RuntimeEnvironment defaultEnvironment() {
        return new RuntimeEnvironment(environment);
    }

    public Duration validatedHeartbeatTimeout() {
        Duration value = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        if (value.compareTo(RuntimeWorker.MIN_HEARTBEAT_TIMEOUT) < 0
                || value.compareTo(RuntimeWorker.MAX_HEARTBEAT_TIMEOUT) > 0) {
            throw new IllegalStateException(
                    "crewscope.runtime.observation.heartbeat-timeout must be between 5 seconds and 10 minutes");
        }
        return value;
    }
}
