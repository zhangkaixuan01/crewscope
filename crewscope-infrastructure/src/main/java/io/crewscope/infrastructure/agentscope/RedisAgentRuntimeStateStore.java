package io.crewscope.infrastructure.agentscope;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.crewscope.application.execution.AgentStateLifecycle;
import io.crewscope.application.execution.AgentStatePreflight;
import io.crewscope.application.execution.AgentStateUnavailableException;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/** Production Redis preflight and explicit lifecycle adapter for AgentScope session state. */
public final class RedisAgentRuntimeStateStore
        implements AgentStatePreflight, AgentStateLifecycle {

    private static final String AGENT_STATE_KEY = "agent_state";

    private final UnifiedJedis jedis;
    private final AgentStateStore stateStore;
    private final RedisSingleActiveExecutionGuard executionGuard;
    private final CrewScopeRedisKeyspace keyspace;
    private final long probeTtlMillis;

    public RedisAgentRuntimeStateStore(
            UnifiedJedis jedis,
            AgentStateStore stateStore,
            RedisSingleActiveExecutionGuard executionGuard,
            CrewScopeRedisKeyspace keyspace,
            Duration probeTtl) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        Duration requiredTtl = Objects.requireNonNull(probeTtl, "probeTtl");
        if (requiredTtl.isZero() || requiredTtl.isNegative() || requiredTtl.toMillis() < 1) {
            throw new IllegalArgumentException("probeTtl must be at least one millisecond");
        }
        this.probeTtlMillis = requiredTtl.toMillis();
    }

    /** Checks owner, Redis health, the exact state slot and an isolated write before model use. */
    @Override
    public void verifyReady(AgentScopeSessionKey sessionKey) {
        AgentScopeSessionKey required = Objects.requireNonNull(sessionKey, "sessionKey");
        executionGuard.requireOwnership();
        try {
            if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                throw new IllegalStateException("Redis health check did not return PONG");
            }
            // AgentScope swallows state-load exceptions, so CrewScope performs this exact read first.
            Optional<AgentState> loaded = stateStore.get(
                    required.userId(), required.sessionId(), AGENT_STATE_KEY, AgentState.class);
            if (loaded.isEmpty()
                    && stateStore.exists(required.userId(), required.sessionId())) {
                throw new IllegalStateException(
                        "The tracked Agent state slot has no complete agent_state value");
            }
            loaded.ifPresent(state -> requireMatchingIdentity(required, state));
            verifyWritable();
            executionGuard.requireOwnership();
        } catch (AgentStateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentStateUnavailableException(exception);
        }
    }

    /** Removes all tracked keys for one archived Session; Agent state itself never receives a TTL. */
    @Override
    public void delete(AgentScopeSessionKey sessionKey) {
        AgentScopeSessionKey required = Objects.requireNonNull(sessionKey, "sessionKey");
        executionGuard.requireOwnership();
        try {
            stateStore.delete(required.userId(), required.sessionId());
        } catch (RuntimeException exception) {
            throw new AgentStateUnavailableException(exception);
        }
    }

    private void verifyWritable() {
        String token = UUID.randomUUID().toString();
        String probeKey = keyspace.writeProbeKey(token);
        try {
            String result = jedis.set(
                    probeKey,
                    token,
                    SetParams.setParams().px(probeTtlMillis));
            if (!"OK".equals(result) || !token.equals(jedis.get(probeKey))) {
                throw new IllegalStateException("Redis write verification failed");
            }
        } finally {
            try {
                jedis.del(probeKey);
            } catch (RuntimeException ignored) {
                // The short TTL cleans a probe that cannot be removed after a Redis failure.
            }
        }
    }

    private static void requireMatchingIdentity(
            AgentScopeSessionKey sessionKey, AgentState state) {
        if (!sessionKey.userId().equals(state.getUserId())
                || !sessionKey.sessionId().equals(state.getSessionId())) {
            throw new IllegalStateException(
                    "The persisted Agent state identity does not match its trusted slot");
        }
    }
}
