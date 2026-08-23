package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable capability key declared or required by an Agent template. */
public record AgentTemplateCapability(String value)
        implements Comparable<AgentTemplateCapability> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

    public AgentTemplateCapability {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "agentTemplate.capability", "must be a lower-case stable capability key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(AgentTemplateCapability other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
