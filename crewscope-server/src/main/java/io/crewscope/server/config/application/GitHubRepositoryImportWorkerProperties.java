package io.crewscope.server.config.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded scheduling and lease settings for GitHub repository imports. */
@ConfigurationProperties(prefix = "crewscope.github-import.worker")
public class GitHubRepositoryImportWorkerProperties {

    private boolean enabled = true;
    private String workerId = "github-import-worker-local";
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofMinutes(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }

    public String validatedWorkerId() {
        String value = Objects.requireNonNull(
                workerId, "crewscope.github-import.worker.worker-id").strip();
        if (value.isEmpty() || value.length() > 160) {
            throw new IllegalStateException(
                    "crewscope.github-import.worker.worker-id must contain 1 to 160 characters");
        }
        return value;
    }

    public Duration validatedPollInterval() {
        return duration(pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1), "poll-interval");
    }

    public Duration validatedLeaseDuration() {
        return duration(leaseDuration, Duration.ofSeconds(5), Duration.ofHours(1), "lease-duration");
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum, String property) {
        Duration required = Objects.requireNonNull(
                value, "crewscope.github-import.worker." + property);
        if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "crewscope.github-import.worker." + property + " is outside its supported range");
        }
        return required;
    }
}
