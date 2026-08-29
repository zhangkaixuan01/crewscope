package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.server.observability.StructuredLogSanitizer;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Stable M7-Q01 public-response and structured-log leakage attack denominator. */
class M7IdentityDisclosureFixedAttackSetM7Q01Test {

    private static final int LEAKAGE_ATTACKS = 16;
    private static final Set<String> FORBIDDEN_RESPONSE_FIELDS = Set.of(
            "password",
            "passwordhash",
            "credential",
            "credentialdigest",
            "tokendigest",
            "sessionid",
            "cookie",
            "authorization",
            "secret");

    @TestFactory
    Stream<DynamicTest> blocksResponseAndLogLeakageAttacks() {
        List<LeakageAttack> attacks = List.of(
                responseShape("LK-01", AuthenticationController.LoginResponse.class),
                responseShape("LK-02", AuthenticationController.AccountSessionView.class),
                responseShape("LK-03", AuthenticationController.PrincipalSessionView.class),
                responseShape("LK-04", AuthenticationController.TeamSessionView.class),
                responseShape("LK-05", AuthenticationController.SessionResponse.class),
                responseShape("LK-06", RegistrationController.RegistrationResponse.class),
                responseShape("LK-07", CurrentAccountController.AccountResponse.class),
                responseShape("LK-08", OnboardingController.OnboardingResponse.class),
                logField("LK-09", "password", "correct horse battery staple"),
                logField("LK-10", "current_password", "current-secret"),
                logField("LK-11", "sessionId", "stolen-session"),
                logField("LK-12", "Cookie", "CREWSCOPE_SESSION=stolen"),
                logField("LK-13", "Authorization", "Bearer stolen-token"),
                logField("LK-14", "loginIdentifier", "alice@example.com"),
                logField("LK-15", "credentialCiphertext", "ciphertext-value"),
                logField("LK-16", "exceptionMessage", "password=private-value"));
        assertStableIds(attacks.stream().map(LeakageAttack::id).toList());
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(attack.id(), attack.assertion()));
    }

    private static LeakageAttack responseShape(String id, Class<?> responseType) {
        return attack(id, () -> {
            assertThat(responseType.isRecord()).isTrue();
            Arrays.stream(responseType.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(name -> name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
                    .forEach(field -> assertThat(FORBIDDEN_RESPONSE_FIELDS.stream()
                                    .noneMatch(field::contains))
                            .as(responseType.getSimpleName() + "." + field)
                            .isTrue());

            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth/login"));
            var response = new ApiExceptionHandler().handle(
                    new IllegalStateException("password=private-value; sessionId=stolen"), exchange);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().toString())
                    .doesNotContain("private-value", "stolen", "IllegalStateException");
        });
    }

    private static LeakageAttack logField(String id, String field, String value) {
        return attack(id, () -> assertThat(StructuredLogSanitizer.sanitize(field, value))
                .isEqualTo(StructuredLogSanitizer.REDACTED));
    }

    private static LeakageAttack attack(
            String id, org.junit.jupiter.api.function.Executable assertion) {
        return new LeakageAttack(id, assertion);
    }

    private static void assertStableIds(List<String> ids) {
        assertThat(ids).hasSize(LEAKAGE_ATTACKS).doesNotHaveDuplicates();
        assertThat(ids).containsExactly(java.util.stream.IntStream.rangeClosed(1, LEAKAGE_ATTACKS)
                .mapToObj(index -> "LK-%02d".formatted(index))
                .toArray(String[]::new));
    }

    private record LeakageAttack(String id, org.junit.jupiter.api.function.Executable assertion) {}
}
