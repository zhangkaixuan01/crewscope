package io.crewscope.domain.agent;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Named key of one frozen preference structure entry (M10-I02a, S01 §3.6): reply language,
 * code style, preferred stack — never an arbitrary conversation excerpt.
 */
public record AgentMemoryKey(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    public AgentMemoryKey {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            throw new DomainValidationException(
                    "agentMemory.memoryKey",
                    "must match ^[a-z0-9][a-z0-9-]{0,62}$");
        }
    }
}
