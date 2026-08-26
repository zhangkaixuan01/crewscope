package io.crewscope.infrastructure.agentscope;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe external configuration for the M2 AgentScope Redis state backend. */
@ConfigurationProperties("crewscope.runtime.redis")
public class AgentStateRedisProperties {

    private boolean enabled = true;
    private String url = "redis://localhost:6379";
    private String environment = "development";
    private String instanceId = "";
    private String ownershipScope = "default";
    private Duration ownershipLease = Duration.ofSeconds(30);
    private Duration ownershipRenewal = Duration.ofSeconds(5);
    private Duration writeProbeTtl = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getOwnershipScope() {
        return ownershipScope;
    }

    public void setOwnershipScope(String ownershipScope) {
        this.ownershipScope = ownershipScope;
    }

    public Duration getOwnershipLease() {
        return ownershipLease;
    }

    public void setOwnershipLease(Duration ownershipLease) {
        this.ownershipLease = ownershipLease;
    }

    public Duration getOwnershipRenewal() {
        return ownershipRenewal;
    }

    public void setOwnershipRenewal(Duration ownershipRenewal) {
        this.ownershipRenewal = ownershipRenewal;
    }

    public Duration getWriteProbeTtl() {
        return writeProbeTtl;
    }

    public void setWriteProbeTtl(Duration writeProbeTtl) {
        this.writeProbeTtl = writeProbeTtl;
    }
}
