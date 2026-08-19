package io.crewscope.infrastructure.workspace.repository;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment retention and transfer limits shared by Coding Artifacts. */
@ConfigurationProperties("crewscope.coding.artifact")
public class CodingArtifactProperties {

    private Duration retention = Duration.ofDays(30);
    private int maximumArtifactBytes = 64 * 1024 * 1024;
    private int maximumRangeBytes = 1024 * 1024;

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getMaximumArtifactBytes() {
        return maximumArtifactBytes;
    }

    public void setMaximumArtifactBytes(int maximumArtifactBytes) {
        this.maximumArtifactBytes = maximumArtifactBytes;
    }

    public int getMaximumRangeBytes() {
        return maximumRangeBytes;
    }

    public void setMaximumRangeBytes(int maximumRangeBytes) {
        this.maximumRangeBytes = maximumRangeBytes;
    }

    void validate() {
        if (retention == null
                || retention.compareTo(Duration.ofHours(1)) < 0
                || retention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException(
                    "Coding Artifact retention must be between 1 hour and 3650 days");
        }
        if (maximumArtifactBytes < 1024 || maximumArtifactBytes > 1024 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "Coding Artifact size limit must be between 1 KiB and 1 GiB");
        }
        if (maximumRangeBytes < 1 || maximumRangeBytes > maximumArtifactBytes) {
            throw new IllegalArgumentException(
                    "Coding Artifact Range limit must fit the Artifact size limit");
        }
    }
}
