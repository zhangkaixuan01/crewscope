package io.crewscope.server.config.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in switch for the optional pgvector knowledge store (M10-I01a). The default
 * {@code false} keeps every non-opting deployment byte-identical to the pre-I01a
 * schema baseline; enabling requires the pgvector postgres image
 * ({@code compose.pgvector.yaml}) alongside this flag.
 */
@ConfigurationProperties(prefix = "crewscope.knowledge.vector")
public class KnowledgeVectorProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
