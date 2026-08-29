package io.crewscope.server.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.env.MockEnvironment;

/** Stable M7-Q01 production Session-cookie downgrade attack denominator. */
class M7CookieFixedAttackSetM7Q01Test {

    private static final int COOKIE_ATTACKS = 8;

    @TestFactory
    Stream<DynamicTest> rejectsSessionCookieDowngrades() {
        List<CookieAttack> attacks = List.of(
                property("CK-01", "server.reactive.session.cookie.name", "JSESSIONID"),
                property("CK-02", "server.reactive.session.cookie.path", "/api"),
                property("CK-03", "server.reactive.session.cookie.http-only", "false"),
                property("CK-04", "server.reactive.session.cookie.same-site", "none"),
                property("CK-05", "server.reactive.session.cookie.same-site", "strict"),
                property("CK-06", "server.reactive.session.cookie.secure", "false"),
                new CookieAttack("CK-07", environment -> environment
                        .withProperty("crewscope.deployment.transport", "local")
                        .withProperty("crewscope.security.login-defense.environment", "demo")
                        .withProperty("server.reactive.session.cookie.secure", "true")),
                property("CK-08", "server.reactive.session.cookie.name", " "));
        assertStableIds(attacks.stream().map(CookieAttack::id).toList());
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(attack.id(), () -> {
            MockEnvironment environment = TeamBetaDeploymentGuardM6I09Test.environment(false);
            attack.mutation().accept(environment);
            assertThatThrownBy(() -> TeamBetaDeploymentGuard.validate(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Team Beta deployment rejected");
        }));
    }

    private static CookieAttack property(String id, String name, String value) {
        return new CookieAttack(id, environment -> environment.withProperty(name, value));
    }

    private static void assertStableIds(List<String> ids) {
        assertThat(ids).hasSize(COOKIE_ATTACKS).doesNotHaveDuplicates();
        assertThat(ids).containsExactly(java.util.stream.IntStream.rangeClosed(1, COOKIE_ATTACKS)
                .mapToObj(index -> "CK-%02d".formatted(index))
                .toArray(String[]::new));
    }

    private record CookieAttack(
            String id, java.util.function.Consumer<MockEnvironment> mutation) {}
}
