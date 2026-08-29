package io.crewscope.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.crewscope.server.api.ApiExceptionHandler;
import io.crewscope.server.config.SecurityConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;

/** Browser/API route matrix and pre-controller rejection contract for M7-A06. */
class AuthenticationRouteSecurityM7A06Test {

  private static final String CORRELATION_ID =
      "8a5dff65-5377-4a27-bb63-3cf892819f8d";

  @Test
  void anonymousApiRequestsUseOneJsonEnvelopeForJsonAndHtmlClients() {
    try (var context = context("bootstrap", false)) {
      WebTestClient client = client(context);

      for (MediaType accept : List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_HTML)) {
        client.get()
            .uri("/api/v1/protected")
            .accept(accept)
            .header("X-Correlation-Id", CORRELATION_ID)
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
            .expectHeader().valueEquals("X-Correlation-Id", CORRELATION_ID)
            .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
            .expectHeader().doesNotExist(HttpHeaders.LOCATION)
            .expectBody()
            .jsonPath("$.code").isEqualTo("authentication_required")
            .jsonPath("$.correlationId").isEqualTo(CORRELATION_ID)
            .jsonPath("$.retryable").isEqualTo(false)
            .jsonPath("$.details").isEmpty();
      }
    }
  }

  @Test
  void oidcModeStillReturnsJsonForApiRequestsWithHtmlAccept() {
    try (var context = context("oidc", true, true)) {
      client(context).get()
          .uri("/api/v1/protected")
          .accept(MediaType.TEXT_HTML)
          .exchange()
          .expectStatus().isUnauthorized()
          .expectHeader().contentType(MediaType.APPLICATION_JSON)
          .expectHeader().doesNotExist(HttpHeaders.LOCATION)
          .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
          .expectBody()
          .jsonPath("$.code").isEqualTo("authentication_required");
    }
  }

  @Test
  void publicSpaEntriesAndAuthenticationBootstrapRoutesRemainAnonymous() {
    try (var context = context("bootstrap", false)) {
      WebTestClient client = client(context);

      for (String path : List.of(
          "/", "/index.html", "/login", "/register", "/invite", "/assets/app.js")) {
        client.get().uri(path).accept(MediaType.TEXT_HTML).exchange().expectStatus().isOk();
      }
      client.get().uri("/api/v1/auth/session").exchange().expectStatus().isOk();
      client.post().uri("/api/v1/auth/login").exchange().expectStatus().isOk();
      client.post().uri("/api/v1/auth/register").exchange().expectStatus().isOk();
      client.post().uri("/api/v1/invitations/preview").exchange().expectStatus().isOk();
    }
  }

  @Test
  void explicitBootstrapCredentialsWorkWithoutEverChallengingBusinessClients() {
    try (var context = context("bootstrap", false)) {
      WebTestClient client = client(context);

      client.get()
          .uri("/api/v1/protected")
          .headers(headers -> headers.setBasicAuth("crewscope", "test-password"))
          .exchange()
          .expectStatus().isOk()
          .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);

      client.get()
          .uri("/api/v1/protected")
          .headers(headers -> headers.setBasicAuth("crewscope", "wrong-password"))
          .exchange()
          .expectStatus().isUnauthorized()
          .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
          .expectBody()
          .jsonPath("$.code").isEqualTo("authentication_required");
    }
  }

  @Test
  void authenticatedButUnauthorizedRequestsUseTheForbiddenEnvelope() {
    try (var context = context("bootstrap", false)) {
      client(context).get()
          .uri("/api/internal/v1/worker/protected")
          .headers(headers -> headers.setBasicAuth("crewscope", "test-password"))
          .exchange()
          .expectStatus().isForbidden()
          .expectHeader().contentType(MediaType.APPLICATION_JSON)
          .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
          .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
          .expectBody()
          .jsonPath("$.code").isEqualTo("access_denied");
    }
  }

  @Test
  void localBrowserWritesWithoutCsrfUseTheCsrfEnvelope() {
    try (var context = context("local", true)) {
      client(context).post()
          .uri("/api/v1/auth/login")
          .exchange()
          .expectStatus().isForbidden()
          .expectHeader().contentType(MediaType.APPLICATION_JSON)
          .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
          .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
          .expectBody()
          .jsonPath("$.code").isEqualTo("csrf_rejected")
          .jsonPath("$.details").isEmpty();
    }
  }

  @Test
  void authenticationBodiesAreRejectedBeforeLargeJsonAggregation() {
    try (var context = context("bootstrap", false)) {
      client(context).post()
          .uri("/api/v1/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("x".repeat((int) AuthenticationRequestBodyLimitWebFilter.LOGIN_BYTES + 1))
          .exchange()
          .expectStatus().isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
          .expectHeader().contentType(MediaType.APPLICATION_JSON)
          .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
          .expectBody()
          .jsonPath("$.code").isEqualTo("request_too_large")
          .jsonPath("$.details").isEmpty();
    }
  }

  @Test
  void wrappedGlobalBufferLimitsRetainThePayloadTooLargeContract() {
    var exchange = MockServerWebExchange.from(
        MockServerHttpRequest.post("/api/v1/auth/register").build());
    var failure = new ServerWebInputException(
        "Request could not be decoded", null, new DataBufferLimitException("limit"));

    var response = new ApiExceptionHandler().handle(failure, exchange);

    assertThat(response.getStatusCode())
        .isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("request_too_large");
    assertThat(response.getBody().details()).isEmpty();
  }

  @Test
  void chunkedAuthenticationBodiesUseTheObservedByteBudget() {
    try (var context = context("bootstrap", false)) {
      client(context).post()
          .uri("/api/v1/auth/login")
          .contentType(MediaType.TEXT_PLAIN)
          .body(Flux.just("x".repeat(4097), "x".repeat(4097)), String.class)
          .exchange()
          .expectStatus().isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
          .expectBody()
          .jsonPath("$.code").isEqualTo("request_too_large");
    }
  }

  @Test
  void crossOriginApiRequestsFailClosedWithoutCorsCredentials() {
    try (var context = context("bootstrap", false)) {
      WebTestClient client = client(context);

      client.get()
          .uri("/api/v1/auth/session")
          .header(HttpHeaders.ORIGIN, "https://attacker.example")
          .exchange()
          .expectStatus().isForbidden()
          .expectHeader().contentType(MediaType.APPLICATION_JSON)
          .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
          .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)
          .expectBody()
          .jsonPath("$.code").isEqualTo("cross_origin_rejected");

      client.get()
          .uri("http://localhost/api/v1/auth/session")
          .header(HttpHeaders.ORIGIN, "http://localhost")
          .exchange()
          .expectStatus().isOk()
          .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }
  }

  @Test
  void canonicalRequestUriPreservesExternalSameOriginWithoutTrustingRawProxyHeaders() {
    try (var context = context("bootstrap", false)) {
      WebTestClient client = client(context);
      client.get()
          .uri("https://crewscope.example/api/v1/auth/session")
          .header(HttpHeaders.ORIGIN, "https://crewscope.example")
          .exchange()
          .expectStatus().isOk();

      client.get()
          .uri("http://localhost/api/v1/auth/session")
          .header(HttpHeaders.ORIGIN, "https://crewscope.example")
          .header("X-Forwarded-Proto", "https")
          .header("X-Forwarded-Host", "crewscope.example")
          .exchange()
          .expectStatus().isForbidden()
          .expectBody()
          .jsonPath("$.code").isEqualTo("cross_origin_rejected");
    }
  }

  @Test
  void applicationResponsesCarryTheFrozenBrowserSecurityHeaders() {
    try (var context = context("bootstrap", false)) {
      var result = client(context).get()
          .uri("https://localhost/api/v1/system/info")
          .exchange()
          .expectStatus().isOk()
          .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
          .expectHeader().valueEquals("X-Frame-Options", "DENY")
          .expectHeader().valueEquals("Referrer-Policy", "same-origin")
          .expectHeader().valueEquals(
              "Permissions-Policy", "camera=(), microphone=(), geolocation=()")
          .expectHeader().valueEquals("Cross-Origin-Opener-Policy", "same-origin")
          .expectHeader().valueEquals("Cross-Origin-Resource-Policy", "same-origin")
          .expectHeader().valueEquals(
              "Content-Security-Policy",
              "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; "
                  + "form-action 'self'; object-src 'none'")
          .expectBody()
          .returnResult();

      assertThat(result.getResponseHeaders().getFirst("Strict-Transport-Security"))
          .contains("max-age=31536000")
          .contains("includeSubDomains");
    }
  }

  @Test
  void monitoringRetainsItsIsolatedMachineBasicChallenge() {
    try (var context = context("bootstrap", false)) {
      client(context).get()
          .uri("/actuator/prometheus")
          .exchange()
          .expectStatus().isUnauthorized()
          .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value ->
              assertThat(value).startsWith("Basic"));
    }
  }

  private static WebTestClient client(AnnotationConfigApplicationContext context) {
    var chains = context.getBeansOfType(SecurityWebFilterChain.class).values();
    return WebTestClient.bindToController(new RouteProbeController())
        .webFilter(new WebFilterChainProxy(List.copyOf(chains)))
        .build();
  }

  private static AnnotationConfigApplicationContext context(
      String mode, boolean browserSessions) {
    return context(mode, browserSessions, false);
  }

  private static AnnotationConfigApplicationContext context(
      String mode, boolean browserSessions, boolean oidcClient) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
            "crewscope.security.mode=" + mode,
            "crewscope.security.bootstrap.username=crewscope",
            "crewscope.security.bootstrap.password=test-password",
            "crewscope.security.monitoring.username=crewscope-prometheus",
            "crewscope.security.monitoring.password=monitoring-password",
            "crewscope.security.oidc.organization-id="
                + ("oidc".equals(mode)
                    ? "d3ff4c9c-7a93-4fc7-91ac-4e3f328acdea"
                    : ""))
        .applyTo(context);
    if (browserSessions) {
      context.registerBean(
          WebSessionServerSecurityContextRepository.class,
          WebSessionServerSecurityContextRepository::new);
    }
    if (oidcClient) {
      context.registerBean(
          ReactiveClientRegistrationRepository.class,
          () -> new InMemoryReactiveClientRegistrationRepository(clientRegistration()));
    }
    context.register(SecurityConfiguration.class);
    context.refresh();
    return context;
  }

  private static ClientRegistration clientRegistration() {
    return ClientRegistration.withRegistrationId("company")
        .clientId("client")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid")
        .authorizationUri("https://id.example.test/oauth2/authorize")
        .tokenUri("https://id.example.test/oauth2/token")
        .jwkSetUri("https://id.example.test/oauth2/jwks")
        .userInfoUri("https://id.example.test/userinfo")
        .userNameAttributeName(IdTokenClaimNames.SUB)
        .clientName("Company")
        .build();
  }

  @RestController
  private static class RouteProbeController {

    @GetMapping({
      "/",
      "/index.html",
      "/login",
      "/register",
      "/invite",
      "/assets/app.js"
    })
    String publicEntry() {
      return "public";
    }

    @GetMapping({
      "/api/v1/system/info",
      "/api/v1/auth/session",
      "/actuator/prometheus"
    })
    String publicApi() {
      return "public-api";
    }

    @PostMapping({
      "/api/v1/auth/login",
      "/api/v1/auth/register",
      "/api/v1/invitations/preview"
    })
    String publicCommand(@RequestBody(required = false) String ignoredBody) {
      return "public-command";
    }

    @GetMapping({"/api/v1/protected", "/api/internal/v1/worker/protected"})
    String protectedApi() {
      return "protected";
    }
  }
}
