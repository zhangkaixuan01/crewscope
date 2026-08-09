package io.crewscope.infrastructure.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.crewscope.application.execution.AgentStateUnavailableException;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.SetParams;

/** Redis 7.4 integration coverage for the production M2-I05 state and ownership adapters. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisAgentStateM2I05IntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final CrewScopeRedisKeyspace KEYSPACE =
            new CrewScopeRedisKeyspace("m2-i05-test");
    private static final AgentScopeSessionKey SESSION_KEY = new AgentScopeSessionKey(
            "crewscope:v1:user:m2-i05-user",
            "crewscope:v1:session:m2-i05-session");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @BeforeEach
    void clearRedis() {
        try (JedisPooled client = newClient()) {
            client.flushDB();
        }
    }

    @Test
    void restoresStableStateThroughANewClientAndDeletesTheCompleteSessionExplicitly() {
        try (JedisPooled firstClient = newClient();
                RedisSingleActiveExecutionGuard firstOwner = owner(firstClient, "first")) {
            firstOwner.start();
            AgentStateStore firstStore = distributedStore(firstClient).agentStateStore();
            RedisAgentRuntimeStateStore firstRuntimeStore =
                    runtimeStore(firstClient, firstStore, firstOwner);
            firstRuntimeStore.verifyReady(SESSION_KEY);
            firstStore.save(
                    SESSION_KEY.userId(),
                    SESSION_KEY.sessionId(),
                    "agent_state",
                    AgentState.builder()
                            .userId(SESSION_KEY.userId())
                            .sessionId(SESSION_KEY.sessionId())
                            .build());
            assertTrue(firstStore.exists(SESSION_KEY.userId(), SESSION_KEY.sessionId()));
            assertTrue(firstClient.keys(KEYSPACE.distributedStorePrefix() + "health:*").isEmpty());
        }

        try (JedisPooled restoredClient = newClient();
                RedisSingleActiveExecutionGuard restoredOwner = owner(restoredClient, "restored")) {
            restoredOwner.start();
            AgentStateStore restoredStore = distributedStore(restoredClient).agentStateStore();
            RedisAgentRuntimeStateStore restoredRuntimeStore =
                    runtimeStore(restoredClient, restoredStore, restoredOwner);

            restoredRuntimeStore.verifyReady(SESSION_KEY);
            AgentState restored = restoredStore
                    .get(
                            SESSION_KEY.userId(),
                            SESSION_KEY.sessionId(),
                            "agent_state",
                            AgentState.class)
                    .orElseThrow();
            assertEquals(SESSION_KEY.userId(), restored.getUserId());
            assertEquals(SESSION_KEY.sessionId(), restored.getSessionId());

            restoredRuntimeStore.delete(SESSION_KEY);
            assertFalse(restoredStore.exists(SESSION_KEY.userId(), SESSION_KEY.sessionId()));
            assertTrue(restoredStore.listSessionIds(SESSION_KEY.userId()).isEmpty());
        }
    }

    @Test
    void rejectsASecondActiveExecutionInstanceWithoutLeakingTheCurrentOwner() {
        try (JedisPooled client = newClient();
                RedisSingleActiveExecutionGuard first = owner(client, "first-instance");
                RedisSingleActiveExecutionGuard second = owner(client, "second-instance")) {
            first.start();

            SingleActiveAgentExecutionException failure = assertThrows(
                    SingleActiveAgentExecutionException.class, second::start);

            assertEquals(
                    "Another CrewScope instance already owns Agent execution",
                    failure.getMessage());
            assertFalse(failure.getMessage().contains("first-instance"));
            assertTrue(first.isActive());
            assertFalse(second.isActive());
        }
    }

    @Test
    void releasesOwnershipOnShutdownAndAllowsTheNextInstanceToStart() {
        try (JedisPooled client = newClient()) {
            RedisSingleActiveExecutionGuard first = owner(client, "first-instance");
            first.start();
            first.close();

            try (RedisSingleActiveExecutionGuard replacement = owner(client, "replacement")) {
                replacement.start();
                assertTrue(replacement.isActive());
            }
        }
    }

    @Test
    void removesACrashedOwnersStaleLeaseByExpiryBeforeReplacementStarts() {
        try (JedisPooled client = newClient()) {
            client.set(
                    KEYSPACE.activeExecutionOwnerKey(),
                    "stale-owner-token",
                    SetParams.setParams().px(20));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (client.exists(KEYSPACE.activeExecutionOwnerKey())
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(client.exists(KEYSPACE.activeExecutionOwnerKey()));

            try (RedisSingleActiveExecutionGuard replacement = owner(client, "after-expiry")) {
                replacement.start();
                assertTrue(replacement.isActive());
            }
        }
    }

    @Test
    void failsClosedWhenTheExactAgentStateCannotBeDecoded() {
        try (JedisPooled client = newClient();
                RedisSingleActiveExecutionGuard owner = owner(client, "corrupt-state")) {
            owner.start();
            AgentStateStore stateStore = distributedStore(client).agentStateStore();
            String slotPrefix = KEYSPACE.distributedStorePrefix()
                    + "session:"
                    + SESSION_KEY.userId()
                    + "/"
                    + SESSION_KEY.sessionId();
            client.set(slotPrefix + ":agent_state", "{not-json");
            client.sadd(slotPrefix + ":_keys", "agent_state");

            RedisAgentRuntimeStateStore runtimeStore =
                    runtimeStore(client, stateStore, owner);

            AgentStateUnavailableException failure = assertThrows(
                    AgentStateUnavailableException.class,
                    () -> runtimeStore.verifyReady(SESSION_KEY));
            assertNull(failure.getCause());
        }
    }

    @Test
    void failsClosedForIncompleteOrMismatchedTrackedState() {
        try (JedisPooled client = newClient();
                RedisSingleActiveExecutionGuard owner = owner(client, "invalid-state")) {
            owner.start();
            AgentStateStore stateStore = distributedStore(client).agentStateStore();
            RedisAgentRuntimeStateStore runtimeStore =
                    runtimeStore(client, stateStore, owner);
            String slotPrefix = KEYSPACE.distributedStorePrefix()
                    + "session:"
                    + SESSION_KEY.userId()
                    + "/"
                    + SESSION_KEY.sessionId();
            client.sadd(slotPrefix + ":_keys", "agent_state");

            assertThrows(
                    AgentStateUnavailableException.class,
                    () -> runtimeStore.verifyReady(SESSION_KEY));

            stateStore.delete(SESSION_KEY.userId(), SESSION_KEY.sessionId());
            stateStore.save(
                    SESSION_KEY.userId(),
                    SESSION_KEY.sessionId(),
                    "agent_state",
                    AgentState.builder()
                            .userId("crewscope:v1:user:foreign")
                            .sessionId("crewscope:v1:session:foreign")
                            .build());
            assertThrows(
                    AgentStateUnavailableException.class,
                    () -> runtimeStore.verifyReady(SESSION_KEY));
        }
    }

    @Test
    void failsClosedAfterOwnershipIsLost() {
        try (JedisPooled client = newClient();
                RedisSingleActiveExecutionGuard owner = owner(client, "lost-owner")) {
            owner.start();
            client.del(KEYSPACE.activeExecutionOwnerKey());

            assertThrows(AgentStateUnavailableException.class, owner::requireOwnership);
            assertFalse(owner.isActive());
        }
    }

    @Test
    void failsClosedWhenRedisBecomesUnavailable() {
        JedisPooled client = newClient();
        RedisSingleActiveExecutionGuard owner = owner(client, "redis-down");
        owner.start();
        AgentStateStore stateStore = distributedStore(client).agentStateStore();
        RedisAgentRuntimeStateStore runtimeStore = runtimeStore(client, stateStore, owner);
        client.close();

        try {
            assertThrows(
                    AgentStateUnavailableException.class,
                    () -> runtimeStore.verifyReady(SESSION_KEY));
        } finally {
            owner.close();
        }
    }

    @Test
    void validatesTheEnvironmentBeforeAnyRedisKeyCanBeBuilt() {
        assertEquals(
                "crewscope:production-cn:agentscope:v1:",
                new CrewScopeRedisKeyspace("production-cn").distributedStorePrefix());
        assertThrows(IllegalArgumentException.class, () -> new CrewScopeRedisKeyspace("../prod"));
        assertThrows(IllegalArgumentException.class, () -> new CrewScopeRedisKeyspace("Prod"));
    }

    private static JedisPooled newClient() {
        return new JedisPooled(URI.create("redis://" + REDIS.getHost() + ":"
                + REDIS.getMappedPort(REDIS_PORT)));
    }

    private static RedisDistributedStore distributedStore(JedisPooled client) {
        return RedisDistributedStore.fromJedis(client, KEYSPACE.distributedStorePrefix());
    }

    private static RedisSingleActiveExecutionGuard owner(
            JedisPooled client, String instanceId) {
        return new RedisSingleActiveExecutionGuard(
                client,
                KEYSPACE,
                instanceId,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5));
    }

    private static RedisAgentRuntimeStateStore runtimeStore(
            JedisPooled client,
            AgentStateStore stateStore,
            RedisSingleActiveExecutionGuard owner) {
        return new RedisAgentRuntimeStateStore(
                client, stateStore, owner, KEYSPACE, Duration.ofSeconds(10));
    }
}
