package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded polling, lease and retry settings for the confirmed Action Worker. */
@ConfigurationProperties(prefix = "crewscope.action.worker")
public class ActionWorkerProperties {

    private boolean enabled = true;
    private String workerId = "action-worker-local";
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration retryDelay = Duration.ofSeconds(15);
    private int batchSize = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String validatedWorkerId() {
        String value = Objects.requireNonNull(workerId, "crewscope.action.worker.worker-id").strip();
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalStateException(
                    "crewscope.action.worker.worker-id must contain 1 to 200 characters");
        }
        return value;
    }

    public Duration validatedLeaseDuration() {
        return duration(leaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(5), "lease-duration");
    }

    public Duration validatedPollInterval() {
        return duration(
                pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1), "poll-interval");
    }

    public Duration validatedRetryDelay() {
        return duration(retryDelay, Duration.ofSeconds(1), Duration.ofMinutes(2), "retry-delay");
    }

    public int validatedBatchSize() {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalStateException(
                    "crewscope.action.worker.batch-size must be between 1 and 100");
        }
        return batchSize;
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum, String property) {
        Duration required = Objects.requireNonNull(value, "crewscope.action.worker." + property);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "crewscope.action.worker." + property + " is outside its supported range");
        }
        return required;
    }
}
