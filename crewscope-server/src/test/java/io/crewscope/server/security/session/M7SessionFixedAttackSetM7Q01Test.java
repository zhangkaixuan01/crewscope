package io.crewscope.server.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.domain.identity.SecurityVersion;
import io.crewscope.domain.identity.UserAccountId;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.mock.env.MockEnvironment;

/** Stable M7-Q01 Session-principal, serializer and cookie-policy attack denominator. */
class M7SessionFixedAttackSetM7Q01Test {

    private static final int SESSION_ATTACKS = 14;

    @TestFactory
    Stream<DynamicTest> blocksSessionFixationAndStateInjectionAttacks() {
        BrowserSessionConfiguration configuration = new BrowserSessionConfiguration();
        RedisSerializer<Object> serializer = configuration.springSessionDefaultRedisSerializer();
        List<SessionAttack> attacks = List.of(
                attack("SS-01", () -> assertThatThrownBy(() -> new BrowserSessionPrincipal(null, 1))
                        .isInstanceOf(NullPointerException.class)),
                attack("SS-02", () -> assertThatThrownBy(() -> new BrowserSessionPrincipal(UUID.randomUUID(), 0))
                        .isInstanceOf(IllegalArgumentException.class)),
                attack("SS-03", () -> assertThatThrownBy(() -> new BrowserSessionPrincipal(UUID.randomUUID(), -1))
                        .isInstanceOf(IllegalArgumentException.class)),
                serializerAttack("SS-04", serializer, new SecurityVersion(1)),
                serializerAttack("SS-05", serializer, UserAccountId.generate()),
                serializerAttack("SS-06", serializer, new UntrustedSessionPayload("credential")),
                serializerAttack(
                        "SS-07",
                        serializer,
                        new UntrustedSessionEnvelope(new UntrustedSessionPayload("credential"))),
                configurationAttack("SS-08", properties -> properties.setMaximumSessions(0), null),
                configurationAttack("SS-09", properties -> properties.setMaximumSessions(21), null),
                configurationAttack("SS-10", properties -> properties.setTtl(Duration.ofMillis(999)), null),
                configurationAttack("SS-11", properties -> properties.setTtl(Duration.ofDays(8)), null),
                configurationAttack("SS-12", properties -> properties.setNamespace("spring:session"), null),
                configurationAttack("SS-13", properties -> properties.setNamespace("CrewScope:Session"), null),
                configurationAttack(
                        "SS-14",
                        properties -> {},
                        environment -> environment.setProperty("spring.session.redis.namespace", "legacy")));
        assertStableIds(attacks.stream().map(SessionAttack::id).toList());
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(attack.id(), attack.assertion()));
    }

    private static SessionAttack serializerAttack(
            String id, RedisSerializer<Object> serializer, Object payload) {
        return attack(id, () -> {
            byte[] encoded = serializer.serialize(payload);
            assertThatThrownBy(() -> serializer.deserialize(encoded))
                    .isInstanceOf(SerializationException.class);
            assertThat(new String(encoded, java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("example-password");
        });
    }

    private static SessionAttack configurationAttack(
            String id,
            java.util.function.Consumer<BrowserSessionProperties> mutateProperties,
            java.util.function.Consumer<MockEnvironment> mutateEnvironment) {
        return attack(id, () -> {
            BrowserSessionProperties properties = properties();
            mutateProperties.accept(properties);
            MockEnvironment environment = environment(properties);
            if (mutateEnvironment != null) {
                mutateEnvironment.accept(environment);
            }
            assertThatThrownBy(() -> BrowserSessionConfiguration.validateConfiguration(
                            environment, properties))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    private static SessionAttack attack(
            String id, org.junit.jupiter.api.function.Executable assertion) {
        return new SessionAttack(id, assertion);
    }

    private static BrowserSessionProperties properties() {
        BrowserSessionProperties properties = new BrowserSessionProperties();
        properties.setTtl(Duration.ofHours(12));
        properties.setMaximumSessions(5);
        properties.setNamespace("crewscope:session");
        return properties;
    }

    private static MockEnvironment environment(BrowserSessionProperties properties) {
        return new MockEnvironment()
                .withProperty("spring.session.timeout", properties.getTtl().toString())
                .withProperty("server.reactive.session.timeout", properties.getTtl().toString())
                .withProperty("spring.session.data.redis.namespace", properties.getNamespace())
                .withProperty("spring.session.data.redis.repository-type", "indexed")
                .withProperty("spring.session.data.redis.save-mode", "on-set-attribute");
    }

    private static void assertStableIds(List<String> ids) {
        assertThat(ids).hasSize(SESSION_ATTACKS).doesNotHaveDuplicates();
        assertThat(ids).containsExactly(java.util.stream.IntStream.rangeClosed(1, SESSION_ATTACKS)
                .mapToObj(index -> "SS-%02d".formatted(index))
                .toArray(String[]::new));
    }

    private record SessionAttack(String id, org.junit.jupiter.api.function.Executable assertion) {}

    private record UntrustedSessionPayload(String credential) implements Serializable {}

    private record UntrustedSessionEnvelope(UntrustedSessionPayload payload) implements Serializable {}
}
