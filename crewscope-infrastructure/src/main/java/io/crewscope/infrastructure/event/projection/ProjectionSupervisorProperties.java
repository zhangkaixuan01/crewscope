package io.crewscope.infrastructure.event.projection;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded runtime settings for one Projection Supervisor instance. */
@ConfigurationProperties("crewscope.projection.supervisor")
public class ProjectionSupervisorProperties {

    private boolean enabled;
    private String instanceId = "projection-supervisor";
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration pollInterval = Duration.ofSeconds(2);
    private Duration retention = Duration.ofDays(7);
    private int pageSize = 250;
    private int claimLimit = 4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getClaimLimit() { return claimLimit; }
    public void setClaimLimit(int claimLimit) { this.claimLimit = claimLimit; }

    public void validate() {
        if (instanceId == null || instanceId.isBlank() || instanceId.strip().length() > 160) {
            throw new IllegalStateException("projection supervisor instance-id is invalid");
        }
        instanceId = instanceId.strip();
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalStateException("projection supervisor durations must be positive");
        }
        if (pageSize < 1 || pageSize > JdbcProjectionEventHistoryStore.MAX_PAGE_SIZE
                || claimLimit < 1 || claimLimit > 100) {
            throw new IllegalStateException("projection supervisor bounds are invalid");
        }
        // Cleanup cannot race a still-valid Worker lease.
        if (retention.compareTo(leaseDuration) <= 0) {
            throw new IllegalStateException("projection supervisor retention must exceed its lease");
        }
    }
}
