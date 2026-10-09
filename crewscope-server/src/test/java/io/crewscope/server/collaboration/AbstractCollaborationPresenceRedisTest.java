package io.crewscope.server.collaboration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared harness for the presence component tests: one disposable Redis per test class, a
 * manually built Lettuce factory (no Spring context, mirroring RedisLoginDefenseM7I04), and
 * a mutable clock so TTL scores are deterministic instead of wall-clock dependent.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class AbstractCollaborationPresenceRedisTest {

  protected static final int REDIS_PORT = 6379;
  protected static final Instant START = Instant.parse("2026-10-09T00:00:00Z");
  protected static final Duration PRESENCE_TTL = Duration.ofSeconds(45);
  protected static final Duration REDIS_TIMEOUT = Duration.ofSeconds(5);

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
          .withExposedPorts(REDIS_PORT)
          .withCommand("redis-server", "--save", "", "--appendonly", "no")
          .waitingFor(Wait.forListeningPort())
          .withStartupTimeout(Duration.ofMinutes(2));

  protected MutableClock clock;
  private LettuceConnectionFactory connections;
  protected ReactiveStringRedisTemplate redis;

  @BeforeEach
  void setUpRedis() throws Exception {
    REDIS.execInContainer("redis-cli", "flushall");
    clock = new MutableClock(START);
    RedisStandaloneConfiguration standalone =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
    LettuceClientConfiguration client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(2000))
            .shutdownTimeout(Duration.ofMillis(100))
            .build();
    connections = new LettuceConnectionFactory(standalone, client);
    connections.afterPropertiesSet();
    connections.start();
    redis = new ReactiveStringRedisTemplate(connections);
  }

  @AfterEach
  void tearDownRedis() {
    if (connections != null) {
      connections.destroy();
    }
  }

  protected static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
