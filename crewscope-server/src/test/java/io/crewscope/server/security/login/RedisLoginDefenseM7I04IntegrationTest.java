package io.crewscope.server.security.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.application.identity.AccountLoginDefenseState;
import io.crewscope.application.identity.AuthenticationFlow;
import io.crewscope.application.identity.ControlledNetworkResource;
import io.crewscope.application.identity.LoginDefenseRequest;
import io.crewscope.application.identity.LoginDefenseUnavailableException;
import io.crewscope.application.identity.LoginIdentifierResource;
import io.crewscope.application.identity.LoginResourceAdmission;
import io.crewscope.domain.identity.LoginAttemptPolicy;
import io.crewscope.domain.identity.UserAccountId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real Redis atomicity, time and outage contract for M7-I04. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisLoginDefenseM7I04IntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final Instant START = Instant.parse("2026-08-28T00:00:00Z");
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    private MutableClock clock;
    private LettuceConnectionFactory connections;
    private RedisLoginDefense defense;

    @BeforeEach
    void setUp() throws Exception {
        REDIS.execInContainer("redis-cli", "flushall");
        clock = new MutableClock(START);
        rebuildClient();
    }

    @AfterEach
    void tearDown() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    @Order(1)
    void enforcesIdentifierAndNetworkWindowsWithInclusiveBoundariesAndFlowIsolation()
            throws Exception {
        LoginDefenseRequest login = request(AuthenticationFlow.LOGIN, "alice@example.com", "net-a");
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(await(defense.admit(login))).isEqualTo(LoginResourceAdmission.ALLOWED);
        }
        assertThat(await(defense.admit(login)))
                .isEqualTo(LoginResourceAdmission.IDENTIFIER_RATE_LIMITED);

        assertThat(await(defense.admit(request(
                        AuthenticationFlow.REGISTRATION, "alice@example.com", "net-a"))))
                .isEqualTo(LoginResourceAdmission.ALLOWED);

        for (int attempt = 0; attempt < 59; attempt++) {
            assertThat(await(defense.admit(request(
                            AuthenticationFlow.LOGIN, "user-" + attempt, "net-b"))))
                    .isEqualTo(LoginResourceAdmission.ALLOWED);
        }
        assertThat(await(defense.admit(request(AuthenticationFlow.LOGIN, "user-59", "net-b"))))
                .isEqualTo(LoginResourceAdmission.ALLOWED);
        assertThat(await(defense.admit(request(AuthenticationFlow.LOGIN, "user-60", "net-b"))))
                .isEqualTo(LoginResourceAdmission.NETWORK_RATE_LIMITED);
    }

    @Test
    @Order(2)
    void releasesTheExactWindowFloorAndRejectsMateriallyBackwardTime() throws Exception {
        LoginDefenseRequest request = request(AuthenticationFlow.LOGIN, "window-user", "net-window");
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(await(defense.admit(request))).isEqualTo(LoginResourceAdmission.ALLOWED);
        }
        clock.advance(Duration.ofMinutes(15));
        assertThat(await(defense.admit(request))).isEqualTo(LoginResourceAdmission.ALLOWED);

        clock.advance(Duration.ofSeconds(-2));
        assertThatThrownBy(() -> await(defense.admit(request)))
                .isExactlyInstanceOf(LoginDefenseUnavailableException.class)
                .hasNoCause();
    }

    @Test
    @Order(3)
    void locksKnownAccountsAtomicallyAndRecoversExactlyAtExpiry() throws Exception {
        UserAccountId account = new UserAccountId(
                UUID.fromString("00000000-0000-0000-0000-000000000704"));
        AccountLoginDefenseState state = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            state = await(defense.recordFailure(account));
            assertThat(state.failureCount()).isEqualTo(attempt);
        }
        assertThat(state).isNotNull();
        assertThat(state.temporarilyLocked()).isTrue();
        AccountLoginDefenseState whileLocked = await(defense.recordFailure(account));
        assertThat(whileLocked.failureCount()).isEqualTo(10);

        clock.advance(Duration.ofMinutes(15));
        AccountLoginDefenseState recovered = await(defense.observeAccount(account));
        assertThat(recovered.temporarilyLocked()).isFalse();
        assertThat(recovered.failureCount()).isZero();
    }

    @Test
    @Order(4)
    void successfulAuthenticationClearsOnlyAccountFailures() throws Exception {
        UserAccountId account = UserAccountId.generate();
        LoginDefenseRequest request = request(AuthenticationFlow.LOGIN, "successful-user", "net-success");
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(await(defense.admit(request))).isEqualTo(LoginResourceAdmission.ALLOWED);
        }
        assertThat(await(defense.recordFailure(account)).failureCount()).isOne();
        assertThat(await(defense.recordSuccess(account)).failureCount()).isZero();
        assertThat(await(defense.admit(request)))
                .isEqualTo(LoginResourceAdmission.IDENTIFIER_RATE_LIMITED);
    }

    @Test
    @Order(5)
    void concurrentRequestsConsumeExactlyTheFixedResourceAndAccountBudgets() throws Exception {
        LoginDefenseRequest request = request(AuthenticationFlow.LOGIN, "parallel-user", "net-parallel");
        List<CompletionStage<LoginResourceAdmission>> stages = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            stages.add(defense.admit(request));
        }
        CompletableFuture.allOf(stages.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture[]::new))
                .get(5, TimeUnit.SECONDS);

        assertThat(stages.stream()
                        .map(stage -> stage.toCompletableFuture().join())
                        .filter(LoginResourceAdmission::allowed))
                .hasSize(10);
        assertThat(stages.stream()
                        .map(stage -> stage.toCompletableFuture().join())
                        .filter(result -> result == LoginResourceAdmission.IDENTIFIER_RATE_LIMITED))
                .hasSize(6);

        UserAccountId account = UserAccountId.generate();
        List<CompletionStage<AccountLoginDefenseState>> failures = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            failures.add(defense.recordFailure(account));
        }
        CompletableFuture.allOf(failures.stream()
                        .map(CompletionStage::toCompletableFuture)
                        .toArray(CompletableFuture[]::new))
                .get(5, TimeUnit.SECONDS);
        AccountLoginDefenseState locked = await(defense.observeAccount(account));
        assertThat(locked.failureCount()).isEqualTo(10);
        assertThat(locked.temporarilyLocked()).isTrue();
    }

    @Test
    @Order(6)
    void redisKeysContainOnlyVersionedDigestsAndOneClusterHashTag() throws Exception {
        String rawIdentifier = "sensitive-user@example.com";
        String rawNetwork = "sensitive-network";
        UserAccountId account = new UserAccountId(
                UUID.fromString("00000000-0000-0000-0000-000000000744"));

        await(defense.admit(request(AuthenticationFlow.LOGIN, rawIdentifier, rawNetwork)));
        await(defense.recordFailure(account));
        String keys = REDIS.execInContainer("redis-cli", "--raw", "keys", "*").getStdout();

        assertThat(keys)
                .contains("crewscope:m7-i04:security:{login-defense}:v1:", ":v1:")
                .doesNotContain(rawIdentifier, rawNetwork, account.value().toString());
    }

    @Test
    @Order(7)
    void rejectsIncompleteOrImpossibleScriptResultsWithoutLeakingTheirContent() {
        assertThatThrownBy(() -> defense.parseAdmission("MALFORMED"))
                .isExactlyInstanceOf(LoginDefenseUnavailableException.class)
                .hasNoCause();
        assertThatThrownBy(() -> defense.parseAccountState(
                        "LOCKED|1787875200000|1787876100000|"))
                .isExactlyInstanceOf(LoginDefenseUnavailableException.class)
                .hasNoCause();
    }

    @Test
    @Order(99)
    void failsClosedDuringRedisLossAndRecoversAfterRestart() throws Exception {
        LoginDefenseRequest request = request(AuthenticationFlow.LOGIN, "restart-user", "net-restart");
        assertThat(await(defense.admit(request))).isEqualTo(LoginResourceAdmission.ALLOWED);

        REDIS.stop();
        assertThatThrownBy(() -> await(defense.admit(request)))
                .isExactlyInstanceOf(LoginDefenseUnavailableException.class)
                .hasNoCause();

        connections.destroy();
        connections = null;
        REDIS.start();
        rebuildClient();
        assertThat(await(defense.admit(request))).isEqualTo(LoginResourceAdmission.ALLOWED);
    }

    private void rebuildClient() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ofMillis(100))
                .build();
        connections = new LettuceConnectionFactory(standalone, client);
        connections.afterPropertiesSet();
        connections.start();
        defense = new RedisLoginDefense(
                new ReactiveStringRedisTemplate(connections),
                new LoginDefenseResourceHasher("v1", SECRET),
                new LoginDefenseKeyspace("m7-i04"),
                LoginAttemptPolicy.standard(),
                clock,
                new LoginDefenseMetrics(new SimpleMeterRegistry()));
    }

    private static LoginDefenseRequest request(
            AuthenticationFlow flow, String identifier, String network) {
        return new LoginDefenseRequest(
                flow,
                LoginIdentifierResource.fromSubmitted(identifier),
                ControlledNetworkResource.ofCanonical(network));
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw failed;
        }
    }

    private static final class MutableClock extends Clock {

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
