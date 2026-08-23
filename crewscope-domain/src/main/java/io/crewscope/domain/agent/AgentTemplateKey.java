package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable lower-case key shared by every appended version of an Agent template. */
public record AgentTemplateKey(String value) implements Comparable<AgentTemplateKey> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public AgentTemplateKey {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "agentTemplate.key", "must be a lower-case stable template key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(AgentTemplateKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
