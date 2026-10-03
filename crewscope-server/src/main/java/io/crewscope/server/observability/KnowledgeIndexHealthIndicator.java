package io.crewscope.server.observability;

import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator projection for the durable knowledge index (M10-I01b). The switch matrix has
 * exactly one illegal combination — {@code crewscope.knowledge.index.enabled=true}
 * without the optional vector store — and it reports {@code DOWN} with the operator
 * action instead of enqueueing jobs that could never embed.
 */
public final class KnowledgeIndexHealthIndicator implements HealthIndicator {

    private final boolean indexEnabled;
    private final boolean vectorEnabled;

    public KnowledgeIndexHealthIndicator(boolean indexEnabled, boolean vectorEnabled) {
        this.indexEnabled = indexEnabled;
        this.vectorEnabled = vectorEnabled;
    }

    @Override
    public Health health() {
        if (indexEnabled && !vectorEnabled) {
            return Health.down()
                    .withDetail("indexEnabled", true)
                    .withDetail("vectorEnabled", false)
                    .withDetail("reason",
                            "crewscope.knowledge.index.enabled requires"
                                    + " crewscope.knowledge.vector.enabled:"
                                    + " refresh enqueues are rejected until the vector"
                                    + " store is enabled")
                    .build();
        }
        return Health.up()
                .withDetail("indexEnabled", indexEnabled)
                .withDetail("vectorEnabled", vectorEnabled)
                .build();
    }
}
