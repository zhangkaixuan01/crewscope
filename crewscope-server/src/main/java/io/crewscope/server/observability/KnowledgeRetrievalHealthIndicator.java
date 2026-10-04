package io.crewscope.server.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator projection for unified knowledge retrieval (M10-A01). Retrieval is always
 * assembled and degrades internally, so the only {@code DOWN} verdict is the switch
 * matrix's illegal combination: {@code crewscope.knowledge.retrieval.enabled=true}
 * without the optional vector stores — a deployment whose every search could only
 * answer {@code RETRIEVAL_DISABLED}.
 */
public final class KnowledgeRetrievalHealthIndicator implements HealthIndicator {

    private final boolean retrievalEnabled;
    private final boolean knowledgeStorePresent;
    private final boolean chunkStorePresent;

    public KnowledgeRetrievalHealthIndicator(
            boolean retrievalEnabled,
            boolean knowledgeStorePresent,
            boolean chunkStorePresent) {
        this.retrievalEnabled = retrievalEnabled;
        this.knowledgeStorePresent = knowledgeStorePresent;
        this.chunkStorePresent = chunkStorePresent;
    }

    @Override
    public Health health() {
        if (retrievalEnabled && !(knowledgeStorePresent && chunkStorePresent)) {
            return Health.down()
                    .withDetail("retrievalEnabled", true)
                    .withDetail("knowledgeStorePresent", knowledgeStorePresent)
                    .withDetail("chunkStorePresent", chunkStorePresent)
                    .withDetail("reason",
                            "crewscope.knowledge.retrieval.enabled requires"
                                    + " crewscope.knowledge.vector.enabled:"
                                    + " every search would answer RETRIEVAL_DISABLED"
                                    + " until the vector store is enabled")
                    .build();
        }
        return Health.up()
                .withDetail("retrievalEnabled", retrievalEnabled)
                .withDetail("knowledgeStorePresent", knowledgeStorePresent)
                .withDetail("chunkStorePresent", chunkStorePresent)
                .build();
    }
}
