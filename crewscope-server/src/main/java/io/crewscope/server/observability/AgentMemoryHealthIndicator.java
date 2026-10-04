package io.crewscope.server.observability;

import io.crewscope.domain.agent.AgentMemoryPolicy;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * M10-I02a Actuator health for the agent assistant memory. The indicator only assembles
 * while {@code crewscope.memory.enabled} is on — a switched-off deployment reports no
 * memory health at all rather than DOWN. While assembled it is always UP: the lifecycle
 * has no queue depth or backlog to degrade on, so the details carry the frozen default
 * policy the space runs under.
 */
public final class AgentMemoryHealthIndicator implements HealthIndicator {

    public AgentMemoryHealthIndicator() {
    }

    @Override
    public Health health() {
        AgentMemoryPolicy policy = AgentMemoryPolicy.defaults();
        return Health.up()
                .withDetail("policyId", policy.policyId().toString())
                .withDetail("policyVersion", policy.version())
                .withDetail("ttlDays", policy.ttlDays())
                .withDetail("maxEntriesPerOwner", policy.maxEntriesPerOwner())
                .withDetail("valueMaxBytes", policy.valueMaxBytes())
                .build();
    }
}
