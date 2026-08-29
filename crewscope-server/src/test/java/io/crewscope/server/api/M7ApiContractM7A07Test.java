package io.crewscope.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.crewscope.application.audit.CrewScopeAuditEventTypes;
import io.crewscope.application.identity.CurrentAccountMutationException;
import io.crewscope.application.identity.CurrentAccountMutationFailure;
import io.crewscope.application.identity.IdentityPersistenceCapacityException;
import io.crewscope.application.identity.LocalAccountLoginException;
import io.crewscope.application.identity.LocalAccountRegistrationException;
import io.crewscope.application.identity.LocalAccountRegistrationFailure;
import io.crewscope.application.team.FirstTeamAlreadyExistsException;
import io.crewscope.application.team.TeamInvitationApplicationException;
import io.crewscope.application.team.TeamInvitationApplicationFailure;
import io.crewscope.domain.identity.AccountIdentifierConflictException;
import io.crewscope.domain.identity.event.AccountLoggedOut;
import io.crewscope.domain.identity.event.AccountPasswordChanged;
import io.crewscope.domain.identity.event.AccountProfileChanged;
import io.crewscope.domain.identity.event.AccountTemporarilyLocked;
import io.crewscope.domain.identity.event.AuthenticationFailuresAggregated;
import io.crewscope.domain.identity.event.AuthenticationSucceeded;
import io.crewscope.domain.identity.event.UserAccountRegistered;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.team.event.TeamInvitationAccepted;
import io.crewscope.domain.team.event.TeamInvitationCreated;
import io.crewscope.domain.team.event.TeamInvitationRevoked;
import jakarta.validation.Valid;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Frozen public DTO, header, error and Audit compatibility contract for M7-A07. */
class M7ApiContractM7A07Test {

    private static final Map<Class<?>, Set<String>> REQUEST_FIELDS = requestFields();

    @Test
    void freezesEveryAuthAccountOnboardingAndInvitationRequestShape() {
        REQUEST_FIELDS.forEach((type, expected) -> {
            assertThat(componentNames(type)).as(type.getSimpleName()).isEqualTo(expected);
            assertThat(Arrays.stream(type.getDeclaredMethods())
                            .anyMatch(method -> method.isAnnotationPresent(
                                    com.fasterxml.jackson.annotation.JsonAnySetter.class)))
                    .as(type.getSimpleName() + " unknown-property guard")
                    .isTrue();
        });

        Set<String> serverOwned = Set.of(
                "organizationId",
                "organization",
                "teamId",
                "team",
                "principalId",
                "principal",
                "accountId",
                "account",
                "memberId",
                "membership",
                "platformRole",
                "role",
                "roles",
                "permissions",
                "authorities",
                "grants",
                "status",
                "version",
                "credentialVersion",
                "passwordHash",
                "tokenDigest",
                "sessionId",
                "cookie");
        REQUEST_FIELDS.keySet().forEach(type -> assertThat(componentNames(type))
                .as(type.getSimpleName())
                .doesNotContainAnyElementsOf(serverOwned));
    }

    @Test
    void unknownFieldsFailThroughTheStableInvalidRequestEnvelope() {
        WebTestClient client = WebTestClient.bindToController(new StrictDtoProbeController())
                .controllerAdvice(new ApiExceptionHandler())
                .build();
        Map<String, String> validBodies = Map.of(
                "login", "{\"identifier\":\"alice\",\"password\":\"secret-value\"}",
                "register", "{\"username\":\"alice\",\"email\":\"alice@example.com\","
                        + "\"displayName\":\"Alice\",\"password\":\"secret-value\"}",
                "profile", "{\"displayName\":\"Alice\"}",
                "password", "{\"currentPassword\":\"old-secret\","
                        + "\"newPassword\":\"new-secret\",\"securityVersion\":1}",
                "sessions", "{\"currentPassword\":\"secret-value\",\"securityVersion\":1}",
                "onboarding", "{\"name\":\"Platform Team\"}",
                "invitation", "{\"targetRole\":\"MEMBER\",\"expiresInMinutes\":60}",
                "token", "{\"token\":\"" + "a".repeat(43) + "\"}");

        validBodies.forEach((route, body) -> client.post()
                .uri("/m7-a07/probe/" + route)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.substring(0, body.length() - 1) + ",\"organizationId\":\"forged\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request")
                .jsonPath("$.message").isEqualTo("Request could not be decoded")
                .jsonPath("$.details").isEmpty()
                .jsonPath("$.organizationId").doesNotExist());
    }

    @Test
    void freezesSingleValueIdempotencyAndStrongVersionHeaders() {
        assertThat(ApiHeaders.requireSingleIdempotencyKey(List.of("m7-a07-command")).value())
                .isEqualTo("m7-a07-command");
        assertThat(ApiHeaders.requireSingleIfMatch(List.of("\"0\""))).isZero();
        assertThat(ApiHeaders.requireSingleIfMatch(List.of("\"9223372036854775807\"")))
                .isEqualTo(Long.MAX_VALUE);
        assertThat(ApiHeaders.versionEtag(17)).isEqualTo("\"17\"");

        assertHeaderFailure(
                () -> ApiHeaders.requireSingleIdempotencyKey(List.of()),
                HttpStatus.BAD_REQUEST,
                "invalid_request");
        assertHeaderFailure(
                () -> ApiHeaders.requireSingleIdempotencyKey(List.of("first", "second")),
                HttpStatus.BAD_REQUEST,
                "invalid_request");
        assertHeaderFailure(
                () -> ApiHeaders.requireSingleIdempotencyKey(List.of("first,second")),
                HttpStatus.BAD_REQUEST,
                "invalid_request");
        assertHeaderFailure(
                () -> ApiHeaders.requireSingleIfMatch(List.of()),
                HttpStatus.PRECONDITION_REQUIRED,
                "precondition_required");
        for (List<String> invalid : List.of(
                List.of("W/\"1\""),
                List.of("*"),
                List.of("\"01\""),
                List.of("\"1\",\"2\""),
                List.of("\"1\"", "\"2\""))) {
            assertHeaderFailure(
                    () -> ApiHeaders.requireSingleIfMatch(invalid),
                    HttpStatus.BAD_REQUEST,
                    "invalid_if_match");
        }
    }

    @Test
    void springHeaderBindingCannotCollapseRepeatedConcurrencyHeaders() {
        WebTestClient client = WebTestClient.bindToController(new StrictDtoProbeController())
                .controllerAdvice(new ApiExceptionHandler())
                .build();

        client.post()
                .uri("/m7-a07/probe/idempotency")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "first", "second")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_request");
        client.post()
                .uri("/m7-a07/probe/if-match")
                .header(ApiHeaders.IF_MATCH, "\"1\"", "\"2\"")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_if_match");
    }

    @Test
    void keepsM7ApplicationFailuresOnThePublishedErrorMatrix() {
        Map<Throwable, ExpectedError> failures = new LinkedHashMap<>();
        failures.put(new LocalAccountLoginException(), error(401, "invalid_credentials"));
        failures.put(new AccountIdentifierConflictException(),
                error(409, "account_identifier_conflict"));
        failures.put(new FirstTeamAlreadyExistsException(), error(409, "onboarding_already_complete"));
        for (LocalAccountRegistrationFailure failure : LocalAccountRegistrationFailure.values()) {
            failures.put(new LocalAccountRegistrationException(failure), switch (failure) {
                case REGISTRATION_DISABLED -> error(403, "registration_unavailable");
                case INVITATION_REQUIRED, INVITATION_INVALID ->
                        error(422, "registration_unavailable");
                case REGISTRATION_CONFLICT -> error(409, "registration_conflict");
                case REPLAY_AUTHENTICATION_FAILED -> error(409, "registration_recovery_failed");
                case REGISTRATION_UNAVAILABLE -> error(503, "registration_unavailable");
            });
        }
        for (CurrentAccountMutationFailure failure : CurrentAccountMutationFailure.values()) {
            failures.put(new CurrentAccountMutationException(failure), switch (failure) {
                case INVALID_CURRENT_PASSWORD -> error(401, "invalid_credentials");
                case SECURITY_VERSION_CONFLICT -> error(409, "security_version_conflict");
                case CREDENTIAL_CONFLICT -> error(409, "account_credential_conflict");
                case ACCOUNT_UNAVAILABLE -> error(503, "account_service_unavailable");
            });
        }
        for (TeamInvitationApplicationFailure failure : TeamInvitationApplicationFailure.values()) {
            failures.put(new TeamInvitationApplicationException(failure), switch (failure) {
                case INVALID_INVITATION -> error(422, "invitation_invalid");
                case INVITATION_NOT_PENDING -> error(409, "invitation_not_pending");
            });
        }

        ApiExceptionHandler handler = new ApiExceptionHandler();
        failures.forEach((failure, expected) -> {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/m7-a07"));
            ResponseEntity<ApiErrorResponse> response = handler.handle(failure, exchange);
            assertThat(response.getStatusCode().value()).isEqualTo(expected.status());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(expected.code());
            assertThat(response.getBody().details()).isEmpty();
            assertThat(response.getBody().toString())
                    .doesNotContain("alice@example.com", "secret", "token", "cookie");
        });
    }

    @Test
    void boundedIdentityPersistenceCapacityUsesRouteSpecificUnavailableCodes() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        Map<String, String> routes = Map.of(
                "/api/v1/auth/register", "registration_unavailable",
                "/api/v1/account", "account_service_unavailable");

        routes.forEach((route, code) -> {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(route));
            ResponseEntity<ApiErrorResponse> response =
                    handler.handle(new IdentityPersistenceCapacityException(), exchange);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(code);
            assertThat(response.getBody().retryable()).isTrue();
            assertThat(response.getBody().details()).isEmpty();
        });
    }

    @Test
    void mapsEveryM7DomainEventToOneReviewedV1AuditDefinition() {
        Map<String, Class<? extends DomainEvent>> events = Map.of(
                "USER_ACCOUNT_REGISTERED", UserAccountRegistered.class,
                "AUTHENTICATION_SUCCEEDED", AuthenticationSucceeded.class,
                "AUTHENTICATION_FAILURES_AGGREGATED", AuthenticationFailuresAggregated.class,
                "ACCOUNT_TEMPORARILY_LOCKED", AccountTemporarilyLocked.class,
                "ACCOUNT_LOGGED_OUT", AccountLoggedOut.class,
                "ACCOUNT_PROFILE_CHANGED", AccountProfileChanged.class,
                "ACCOUNT_PASSWORD_CHANGED", AccountPasswordChanged.class,
                "TEAM_INVITATION_CREATED", TeamInvitationCreated.class,
                "TEAM_INVITATION_ACCEPTED", TeamInvitationAccepted.class,
                "TEAM_INVITATION_REVOKED", TeamInvitationRevoked.class);
        var registry = CrewScopeAuditEventTypes.reviewedRegistry();

        events.forEach((type, payload) -> {
            var definition = registry
                    .find(EventType.from(type), SchemaVersion.V1)
                    .orElseThrow();
            assertThat(definition.allowedSourceFields()).isEqualTo(componentNames(payload));
            assertThat(registry.find(EventType.from(type), SchemaVersion.V2)).isEmpty();
        });
    }

    @Test
    void responseShapesContainNoCredentialDigestOrBrowserSessionIdentifier() {
        List<Class<?>> responseTypes = List.of(
                AuthenticationController.LoginResponse.class,
                AuthenticationController.AccountSessionView.class,
                AuthenticationController.PrincipalSessionView.class,
                AuthenticationController.TeamSessionView.class,
                AuthenticationController.SessionResponse.class,
                RegistrationController.RegistrationResponse.class,
                CurrentAccountController.AccountResponse.class,
                OnboardingController.OnboardingResponse.class,
                TeamInvitationController.InvitationPageResponse.class,
                TeamInvitationController.InvitationResponse.class,
                TeamInvitationController.InvitationPreviewResponse.class,
                CommandReceiptResponse.class);
        Set<String> forbiddenFragments = Set.of(
                "password",
                "credential",
                "token",
                "sessionid",
                "cookie",
                "authorization",
                "secret");

        responseTypes.forEach(type -> componentNames(type).forEach(field ->
                assertThat(forbiddenFragments.stream()
                                .noneMatch(field.toLowerCase(java.util.Locale.ROOT)::contains))
                        .as(type.getSimpleName() + "." + field)
                        .isTrue()));
        assertThat(componentNames(TeamInvitationController.InvitationCreationResponse.class))
                .containsExactlyInAnyOrder("command", "invitation", "token")
                .doesNotContain("tokenDigest");
        assertThat(componentNames(AuthenticationController.CsrfCoordinates.class))
                .containsExactlyInAnyOrder("headerName", "parameterName", "token");
    }

    @Test
    void documentsEveryFrozenRouteHeaderErrorAndAuditCoordinate() throws IOException {
        String contract = Files.readString(repositoryRoot()
                .resolve("docs/api/M7-开放用户API契约.md"));
        for (String route : List.of(
                "/api/v1/auth/register",
                "/api/v1/auth/login",
                "/api/v1/auth/logout",
                "/api/v1/auth/session",
                "/api/v1/account",
                "/api/v1/account/password",
                "/api/v1/account/sessions/revoke",
                "/api/v1/onboarding",
                "/api/v1/onboarding/team",
                "/api/v1/invitations/preview",
                "/api/v1/invitations/accept")) {
            assertThat(contract).contains(route);
        }
        for (String coordinate : List.of(
                "Idempotency-Key",
                "Idempotency-Replayed: true",
                "If-Match",
                "precondition_required",
                "invalid_if_match",
                "invalid_request",
                "USER_ACCOUNT_REGISTERED",
                "ACCOUNT_PASSWORD_CHANGED",
                "TEAM_INVITATION_ACCEPTED")) {
            assertThat(contract).contains(coordinate);
        }
    }

    private static void assertHeaderFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            HttpStatus status,
            String code) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ApiRequestException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(status);
                    assertThat(failure.code()).isEqualTo(code);
                });
    }

    private static Set<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ExpectedError error(int status, String code) {
        return new ExpectedError(status, code);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null
                && !Files.isRegularFile(current.resolve("docs/api/M7-开放用户API契约.md"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("CrewScope repository root is unavailable");
        }
        return current;
    }

    private static Map<Class<?>, Set<String>> requestFields() {
        Map<Class<?>, Set<String>> fields = new LinkedHashMap<>();
        fields.put(AuthenticationController.LoginRequest.class, Set.of("identifier", "password"));
        fields.put(RegistrationController.RegistrationRequest.class,
                Set.of("username", "email", "displayName", "password", "invitationToken"));
        fields.put(CurrentAccountController.ProfileUpdateRequest.class,
                Set.of("username", "email", "displayName", "currentPassword", "securityVersion"));
        fields.put(CurrentAccountController.PasswordChangeRequest.class,
                Set.of("currentPassword", "newPassword", "securityVersion"));
        fields.put(CurrentAccountController.SessionRevocationRequest.class,
                Set.of("currentPassword", "securityVersion"));
        fields.put(OnboardingController.CreateFirstTeamRequest.class, Set.of("name"));
        fields.put(TeamInvitationController.CreateInvitationRequest.class,
                Set.of("targetEmail", "targetRole", "expiresInMinutes"));
        fields.put(TeamInvitationController.InvitationTokenRequest.class, Set.of("token"));
        return Map.copyOf(fields);
    }

    private record ExpectedError(int status, String code) {}

    /** Exercises Spring WebFlux's configured Jackson 3 decoder rather than a standalone mapper. */
    @RestController
    @RequestMapping("/m7-a07/probe")
    static final class StrictDtoProbeController {

        @PostMapping("/login")
        void login(@Valid @RequestBody AuthenticationController.LoginRequest ignored) {}

        @PostMapping("/register")
        void register(@Valid @RequestBody RegistrationController.RegistrationRequest ignored) {}

        @PostMapping("/profile")
        void profile(@Valid @RequestBody CurrentAccountController.ProfileUpdateRequest ignored) {}

        @PostMapping("/password")
        void password(@Valid @RequestBody CurrentAccountController.PasswordChangeRequest ignored) {}

        @PostMapping("/sessions")
        void sessions(@Valid @RequestBody CurrentAccountController.SessionRevocationRequest ignored) {}

        @PostMapping("/onboarding")
        void onboarding(@Valid @RequestBody OnboardingController.CreateFirstTeamRequest ignored) {}

        @PostMapping("/invitation")
        void invitation(@Valid @RequestBody TeamInvitationController.CreateInvitationRequest ignored) {}

        @PostMapping("/token")
        void token(@Valid @RequestBody TeamInvitationController.InvitationTokenRequest ignored) {}

        @PostMapping("/idempotency")
        void idempotency(
                @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false)
                        List<String> values) {
            ApiHeaders.requireSingleIdempotencyKey(values);
        }

        @PostMapping("/if-match")
        void ifMatch(
                @RequestHeader(name = ApiHeaders.IF_MATCH, required = false)
                        List<String> values) {
            ApiHeaders.requireSingleIfMatch(values);
        }
    }
}
