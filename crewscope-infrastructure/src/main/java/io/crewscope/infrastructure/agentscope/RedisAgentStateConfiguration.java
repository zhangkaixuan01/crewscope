package io.crewscope.infrastructure.agentscope;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

/** Creates the production AgentScope Redis store and M2 single-owner execution guard. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "crewscope.runtime.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(AgentStateRedisProperties.class)
public class RedisAgentStateConfiguration {

    @Bean
    CrewScopeRedisKeyspace crewScopeRedisKeyspace(AgentStateRedisProperties properties) {
        return new CrewScopeRedisKeyspace(properties.getEnvironment());
    }

    @Bean(destroyMethod = "close")
    JedisPooled crewScopeAgentStateRedisClient(AgentStateRedisProperties properties) {
        return new JedisPooled(URI.create(properties.getUrl()));
    }

    @Bean
    RedisDistributedStore crewScopeRedisDistributedStore(
            JedisPooled redisClient, CrewScopeRedisKeyspace keyspace) {
        return RedisDistributedStore.fromJedis(redisClient, keyspace.distributedStorePrefix());
    }

    @Bean(destroyMethod = "")
    AgentStateStore crewScopeAgentStateStore(RedisDistributedStore distributedStore) {
        // The JedisPooled bean exclusively owns connection shutdown.
        return distributedStore.agentStateStore();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    RedisSingleActiveExecutionGuard redisSingleActiveExecutionGuard(
            JedisPooled redisClient,
            CrewScopeRedisKeyspace keyspace,
            AgentStateRedisProperties properties) {
        String configuredInstanceId = properties.getInstanceId();
        String instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? UUID.randomUUID().toString()
                : configuredInstanceId.strip();
        return new RedisSingleActiveExecutionGuard(
                redisClient,
                keyspace,
                instanceId,
                properties.getOwnershipLease(),
                properties.getOwnershipRenewal());
    }

    @Bean
    RedisAgentRuntimeStateStore redisAgentRuntimeStateStore(
            JedisPooled redisClient,
            AgentStateStore stateStore,
            RedisSingleActiveExecutionGuard executionGuard,
            CrewScopeRedisKeyspace keyspace,
            AgentStateRedisProperties properties) {
        return new RedisAgentRuntimeStateStore(
                redisClient,
                stateStore,
                executionGuard,
                keyspace,
                properties.getWriteProbeTtl());
    }
}
