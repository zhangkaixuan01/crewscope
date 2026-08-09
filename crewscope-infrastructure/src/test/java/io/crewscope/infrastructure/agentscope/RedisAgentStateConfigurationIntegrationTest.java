package io.crewscope.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.crewscope.application.execution.AgentStateLifecycle;
import io.crewscope.application.execution.AgentStatePreflight;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Proves that Spring creates one Redis state graph and rejects a competing application context. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisAgentStateConfigurationIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void wiresTheProductionRedisStateGraphExactlyOnce() {
        runner("spring-owner").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CrewScopeRedisKeyspace.class);
            assertThat(context).hasSingleBean(RedisDistributedStore.class);
            assertThat(context).hasSingleBean(AgentStateStore.class);
            assertThat(context).hasSingleBean(RedisSingleActiveExecutionGuard.class);
            assertThat(context).hasSingleBean(RedisAgentRuntimeStateStore.class);
            assertThat(context).hasSingleBean(AgentStatePreflight.class);
            assertThat(context).hasSingleBean(AgentStateLifecycle.class);
            assertThat(context.getBean(RedisSingleActiveExecutionGuard.class).isActive()).isTrue();
        });
    }

    @Test
    void rejectsASecondSpringContextWhileTheFirstStillOwnsExecution() {
        runner("first-spring-owner").run(first -> {
            assertThat(first).hasNotFailed();

            runner("second-spring-owner").run(second -> assertThat(second)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseInstanceOf(SingleActiveAgentExecutionException.class));
        });
    }

    private static ApplicationContextRunner runner(String instanceId) {
        String redisUrl = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT);
        return new ApplicationContextRunner()
                .withUserConfiguration(RedisAgentStateConfiguration.class)
                .withPropertyValues(
                        "crewscope.runtime.redis.url=" + redisUrl,
                        "crewscope.runtime.redis.environment=m2-i05-spring",
                        "crewscope.runtime.redis.instance-id=" + instanceId,
                        "crewscope.runtime.redis.ownership-lease=30s",
                        "crewscope.runtime.redis.ownership-renewal=5s",
                        "crewscope.runtime.redis.write-probe-ttl=10s");
    }
}
