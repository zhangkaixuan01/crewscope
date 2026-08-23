package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/** Immutable declared Agent capabilities and model capabilities required to run them. */
public final class AgentTemplateCapabilities {

    private final Set<AgentTemplateCapability> declaredCapabilities;
    private final Set<AgentTemplateCapability> requiredModelCapabilities;
    private final AgentTemplateHash capabilityHash;

    private AgentTemplateCapabilities(
            Set<AgentTemplateCapability> declaredCapabilities,
            Set<AgentTemplateCapability> requiredModelCapabilities,
            AgentTemplateHash expectedHash) {
        this.declaredCapabilities = Set.copyOf(
                Objects.requireNonNull(declaredCapabilities, "declaredCapabilities"));
        this.requiredModelCapabilities = Set.copyOf(
                Objects.requireNonNull(requiredModelCapabilities, "requiredModelCapabilities"));
        if (this.declaredCapabilities.isEmpty()) {
            throw new DomainValidationException(
                    "agentTemplate.declaredCapabilities", "must not be empty");
        }
        this.capabilityHash = calculateHash();
        if (expectedHash != null && !expectedHash.equals(this.capabilityHash)) {
            throw new DomainValidationException(
                    "agentTemplate.capabilityHash",
                    "must match the canonical capability facts");
        }
    }

    public static AgentTemplateCapabilities define(
            Set<AgentTemplateCapability> declaredCapabilities,
            Set<AgentTemplateCapability> requiredModelCapabilities) {
        return new AgentTemplateCapabilities(
                declaredCapabilities, requiredModelCapabilities, null);
    }

    public static AgentTemplateCapabilities reconstitute(
            Set<AgentTemplateCapability> declaredCapabilities,
            Set<AgentTemplateCapability> requiredModelCapabilities,
            AgentTemplateHash capabilityHash) {
        return new AgentTemplateCapabilities(
                declaredCapabilities,
                requiredModelCapabilities,
                Objects.requireNonNull(capabilityHash, "capabilityHash"));
    }

    private AgentTemplateHash calculateHash() {
        StringBuilder canonical = new StringBuilder("agent-template-capabilities-v1");
        declaredCapabilities.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> AgentTemplateHash.append(canonical, "declared:" + value));
        requiredModelCapabilities.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> AgentTemplateHash.append(canonical, "model:" + value));
        return AgentTemplateHash.sha256(canonical.toString());
    }

    public Set<AgentTemplateCapability> declaredCapabilities() {
        return declaredCapabilities;
    }

    public Set<AgentTemplateCapability> requiredModelCapabilities() {
        return requiredModelCapabilities;
    }

    public AgentTemplateHash capabilityHash() {
        return capabilityHash;
    }
}
