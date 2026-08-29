package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.ControlledNetworkResource;
import io.crewscope.application.identity.LocalAccountRegistrationService;
import io.crewscope.application.identity.LoginDefense;
import io.crewscope.application.identity.LoginResourceAdmission;
import io.crewscope.domain.identity.RegistrationMode;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.server.config.RegistrationProperties;
import io.crewscope.server.security.login.ControlledNetworkSourceResolver;
import io.crewscope.server.security.session.BrowserSessionLifecycle;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Anonymous transport, defense and secret-minimization contract for M7-A01. */
class RegistrationControllerM7A01Test {

  private LocalAccountRegistrationService registrations;
  private LoginDefense defense;
  private WebTestClient client;

  @BeforeEach
  void setUp() {
    registrations = mock(LocalAccountRegistrationService.class);
    defense = mock(LoginDefense.class);
    ControlledNetworkSourceResolver networks = mock(ControlledNetworkSourceResolver.class);
    when(networks.resolve(any())).thenReturn(ControlledNetworkResource.ofCanonical("ipv4:7f000000/24"));
    BrowserSessionLifecycle sessions = mock(BrowserSessionLifecycle.class);
    RegistrationProperties properties = new RegistrationProperties();
    properties.setMode(RegistrationMode.OPEN);
    properties.setOrganizationId(OrganizationId.generate().toString());
    client = WebTestClient.bindToController(new RegistrationController(
            registrations,
            properties,
            provider(defense),
            provider(networks),
            provider(sessions)))
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void foldsRegistrationDefenseLimitsBeforePersistence() {
    when(defense.admit(any())).thenReturn(CompletableFuture.completedFuture(
        LoginResourceAdmission.IDENTIFIER_AND_NETWORK_RATE_LIMITED));

    client.post()
        .uri("/api/v1/auth/register")
        .header(ApiHeaders.IDEMPOTENCY_KEY, "register-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(validBody())
        .exchange()
        .expectStatus().isEqualTo(429)
        .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
        .expectBody()
        .jsonPath("$.code").isEqualTo("too_many_requests")
        .jsonPath("$.details").isEmpty();

    verify(registrations, never()).register(any(), any());
  }

  @Test
  void requiresAValidIdempotencyKeyWithoutEchoingSecrets() {
    client.post()
        .uri("/api/v1/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(validBody())
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_request")
        .jsonPath("$.message").value(message ->
            org.junit.jupiter.api.Assertions.assertFalse(message.toString().contains("Secret")));
  }

  private static String validBody() {
    return """
        {"username":"alice","email":"alice@example.com","displayName":"Alice",
         "password":"Correct-Horse-Battery-Staple"}
        """;
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
