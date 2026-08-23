package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded UNKNOWN recovery, takeover and human escalation settings. */
@ConfigurationProperties(prefix = "crewscope.action.reconciliation")
public class ActionReconciliationProperties {

    private boolean enabled = true;
    private boolean startupEnabled = true;
    private String workerId = "action-reconciler-local";
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration retryDelay = Duration.ofSeconds(30);
    private Duration maximumUnknownAge = Duration.ofHours(1);
    private int maximumAttempts = 5;
    private int batchSize = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStartupEnabled() {
        return startupEnabled;
    }

    public void setStartupEnabled(boolean startupEnabled) {
        this.startupEnabled = startupEnabled;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
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

    public Duration getMaximumUnknownAge() {
        return maximumUnknownAge;
    }

    public void setMaximumUnknownAge(Duration maximumUnknownAge) {
        this.maximumUnknownAge = maximumUnknownAge;
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        this.maximumAttempts = maximumAttempts;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String validatedWorkerId() {
        String value = Objects.requireNonNull(
                        workerId, "crewscope.action.reconciliation.worker-id")
                .strip();
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalStateException(
                    "crewscope.action.reconciliation.worker-id must contain 1 to 200 characters");
        }
        return value;
    }

    public Duration validatedPollInterval() {
        return duration(
                pollInterval, Duration.ofMillis(100), Duration.ofMinutes(5), "poll-interval");
    }

    public Duration validatedLeaseDuration() {
        return duration(
                leaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(5), "lease-duration");
    }

    public Duration validatedRetryDelay() {
        return duration(
                retryDelay, Duration.ofSeconds(1), Duration.ofMinutes(30), "retry-delay");
    }

    public Duration validatedMaximumUnknownAge() {
        return duration(
                maximumUnknownAge, Duration.ofMinutes(1), Duration.ofDays(7), "maximum-unknown-age");
    }

    public int validatedMaximumAttempts() {
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalStateException(
                    "crewscope.action.reconciliation.maximum-attempts must be between 1 and 100");
        }
        return maximumAttempts;
    }

    public int validatedBatchSize() {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalStateException(
                    "crewscope.action.reconciliation.batch-size must be between 1 and 100");
        }
        return batchSize;
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum, String property) {
        Duration required = Objects.requireNonNull(
                value, "crewscope.action.reconciliation." + property);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "crewscope.action.reconciliation." + property
                            + " is outside its supported range");
        }
        return required;
    }
}
