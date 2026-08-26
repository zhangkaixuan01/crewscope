package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded settings for notification writes and query-only recovery. */
@ConfigurationProperties(prefix = "crewscope.notification.worker")
public class NotificationWorkerProperties {

    private boolean enabled = true;
    private boolean reconciliationEnabled = true;
    private boolean redeliveryEnabled = true;
    private String workerId = "notification-worker-local";
    private String reconciliationWorkerId = "notification-reconciler-local";
    private String redeliveryWorkerId = "notification-redelivery-local";
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration reconciliationPollInterval = Duration.ofSeconds(5);
    private Duration redeliveryPollInterval = Duration.ofSeconds(2);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration credentialTtl = Duration.ofSeconds(30);
    private Duration retryDelay = Duration.ofSeconds(15);
    private Duration maximumRetryDelay = Duration.ofMinutes(15);
    private Duration reconciliationRetryDelay = Duration.ofSeconds(30);
    private int maximumAttempts = 5;
    private int reconciliationMaximumAttempts = 5;
    private int batchSize = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReconciliationEnabled() { return reconciliationEnabled; }
    public void setReconciliationEnabled(boolean value) { reconciliationEnabled = value; }
    public boolean isRedeliveryEnabled() { return redeliveryEnabled; }
    public void setRedeliveryEnabled(boolean value) { redeliveryEnabled = value; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String value) { workerId = value; }
    public String getReconciliationWorkerId() { return reconciliationWorkerId; }
    public void setReconciliationWorkerId(String value) { reconciliationWorkerId = value; }
    public String getRedeliveryWorkerId() { return redeliveryWorkerId; }
    public void setRedeliveryWorkerId(String value) { redeliveryWorkerId = value; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration value) { pollInterval = value; }
    public Duration getReconciliationPollInterval() { return reconciliationPollInterval; }
    public void setReconciliationPollInterval(Duration value) { reconciliationPollInterval = value; }
    public Duration getRedeliveryPollInterval() { return redeliveryPollInterval; }
    public void setRedeliveryPollInterval(Duration value) { redeliveryPollInterval = value; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
    public Duration getCredentialTtl() { return credentialTtl; }
    public void setCredentialTtl(Duration value) { credentialTtl = value; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration value) { retryDelay = value; }
    public Duration getMaximumRetryDelay() { return maximumRetryDelay; }
    public void setMaximumRetryDelay(Duration value) { maximumRetryDelay = value; }
    public Duration getReconciliationRetryDelay() { return reconciliationRetryDelay; }
    public void setReconciliationRetryDelay(Duration value) { reconciliationRetryDelay = value; }
    public int getMaximumAttempts() { return maximumAttempts; }
    public void setMaximumAttempts(int value) { maximumAttempts = value; }
    public int getReconciliationMaximumAttempts() { return reconciliationMaximumAttempts; }
    public void setReconciliationMaximumAttempts(int value) { reconciliationMaximumAttempts = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }

    public String validatedWorkerId() { return workerId(workerId, "worker-id"); }
    public String validatedReconciliationWorkerId() {
        return workerId(reconciliationWorkerId, "reconciliation-worker-id");
    }
    public String validatedRedeliveryWorkerId() {
        return workerId(redeliveryWorkerId, "redelivery-worker-id");
    }
    public Duration validatedPollInterval() {
        return duration(pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1), "poll-interval");
    }
    public Duration validatedReconciliationPollInterval() {
        return duration(
                reconciliationPollInterval, Duration.ofMillis(100), Duration.ofMinutes(5),
                "reconciliation-poll-interval");
    }
    public Duration validatedRedeliveryPollInterval() {
        return duration(
                redeliveryPollInterval, Duration.ofMillis(100), Duration.ofMinutes(5),
                "redelivery-poll-interval");
    }
    public Duration validatedLeaseDuration() {
        return duration(leaseDuration, Duration.ofSeconds(5), Duration.ofMinutes(10), "lease-duration");
    }
    public Duration validatedCredentialTtl() {
        return duration(credentialTtl, Duration.ofSeconds(1), validatedLeaseDuration(), "credential-ttl");
    }
    public Duration validatedRetryDelay() {
        return duration(retryDelay, Duration.ofSeconds(1), Duration.ofMinutes(30), "retry-delay");
    }
    public Duration validatedMaximumRetryDelay() {
        return duration(
                maximumRetryDelay, validatedRetryDelay(), Duration.ofHours(6),
                "maximum-retry-delay");
    }
    public Duration validatedReconciliationRetryDelay() {
        return duration(
                reconciliationRetryDelay, Duration.ofSeconds(1), Duration.ofHours(1),
                "reconciliation-retry-delay");
    }
    public int validatedMaximumAttempts() { return count(maximumAttempts, "maximum-attempts"); }
    public int validatedReconciliationMaximumAttempts() {
        return count(reconciliationMaximumAttempts, "reconciliation-maximum-attempts");
    }
    public int validatedBatchSize() { return count(batchSize, "batch-size"); }

    private static String workerId(String raw, String property) {
        String value = Objects.requireNonNull(raw, property).strip();
        if (value.isBlank() || value.length() > 160) {
            throw new IllegalStateException(property + " must contain 1 to 160 characters");
        }
        return value;
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum, String property) {
        Duration required = Objects.requireNonNull(value, property);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(property + " is outside its supported range");
        }
        return required;
    }

    private static int count(int value, String property) {
        if (value < 1 || value > 100) {
            throw new IllegalStateException(property + " must be between 1 and 100");
        }
        return value;
    }
}
