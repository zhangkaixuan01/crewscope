package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Exact immutable coordinate of one appended Agent template definition. */
public record AgentTemplateVersion(AgentTemplateKey key, long version) {

    public AgentTemplateVersion {
        key = Objects.requireNonNull(key, "key");
        if (version < 1) {
            throw new DomainValidationException(
                    "agentTemplate.version", "must be positive");
        }
    }

    public static AgentTemplateVersion of(String key, long version) {
        return new AgentTemplateVersion(new AgentTemplateKey(key), version);
    }

    public AgentTemplateVersion next() {
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "agentTemplate.version", "must not overflow");
        }
        return new AgentTemplateVersion(key, version + 1);
    }

    @Override
    public String toString() {
        return key + "@" + version;
    }
}
