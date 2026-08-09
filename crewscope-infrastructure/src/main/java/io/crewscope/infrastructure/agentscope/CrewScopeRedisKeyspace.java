package io.crewscope.infrastructure.agentscope;

import java.util.Objects;
import java.util.regex.Pattern;

/** Builds the environment-isolated Redis keyspace fixed by the M2 state schema. */
public final class CrewScopeRedisKeyspace {

    private static final Pattern ENVIRONMENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private final String basePrefix;

    public CrewScopeRedisKeyspace(String environment) {
        String normalized = Objects.requireNonNull(environment, "environment").strip();
        if (!ENVIRONMENT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Redis environment must use 1 to 32 lowercase letters, digits or hyphens");
        }
        this.basePrefix = "crewscope:" + normalized + ":agentscope:v1:";
    }

    /** Prefix passed to AgentScope RedisDistributedStore before it appends its component names. */
    public String distributedStorePrefix() {
        return basePrefix;
    }

    public String activeExecutionOwnerKey() {
        return basePrefix + "ownership:active-instance";
    }

    public String writeProbeKey(String token) {
        String required = Objects.requireNonNull(token, "token").strip();
        if (required.isEmpty()) {
            throw new IllegalArgumentException("probe token must not be blank");
        }
        return basePrefix + "health:write-probe:" + required;
    }
}
