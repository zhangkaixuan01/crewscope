package io.crewscope.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import org.springframework.web.bind.annotation.RestController;

class SecurityConfigurationTest {

  @Test
  void buildsTheBootstrapProfile() {
    try (var context = context("bootstrap", false)) {
      assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(2);
    }
  }

  @Test
  void bootstrapProfileDoesNotChallengeOrdinaryUnauthenticatedWebRequests() {
    try (var context = context("bootstrap", false)) {
      var chains = context.getBeansOfType(SecurityWebFilterChain.class).values();
      var response =
          WebTestClient.bindToController(new ProtectedProbeController())
              .webFilter(new WebFilterChainProxy(List.copyOf(chains)))
              .build()
              .get()
              .uri("/protected-probe")
              .exchange()
              .expectStatus()
              .isUnauthorized()
              .expectBody()
              .returnResult();

      assertThat(response.getResponseHeaders().getFirst("WWW-Authenticate")).isNull();
    }
  }

  @Test
  void buildsTheOidcProfileWhenAClientRegistrationExists() {
    try (var context = context("oidc", true)) {
      assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(2);
    }
  }

  @Test
  void rejectsTheOidcProfileWhenClientRegistrationIsMissing() {
    assertThrows(Exception.class, () -> context("oidc", false));
  }

  @Test
  void rejectsTheOidcProfileWhenOrganizationBindingIsMissing() {
    assertThrows(Exception.class, () -> context("oidc", true, false));
  }

  @Test
  void rejectsAnUnknownProfileDuringStartup() {
    assertThrows(Exception.class, () -> context("legacy", false));
  }

  private static AnnotationConfigApplicationContext context(
      String mode, boolean registerOidcClient) {
    return context(mode, registerOidcClient, true);
  }

  private static AnnotationConfigApplicationContext context(
      String mode, boolean registerOidcClient, boolean bindOidcOrganization) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
            "crewscope.security.mode=" + mode,
            "crewscope.security.bootstrap.username=crewscope",
            "crewscope.security.bootstrap.password=test-password",
            "crewscope.security.monitoring.username=crewscope-prometheus",
            "crewscope.security.monitoring.password=monitoring-password",
            "crewscope.security.oidc.organization-id="
                + (mode.equals("oidc") && bindOidcOrganization
                    ? "d3ff4c9c-7a93-4fc7-91ac-4e3f328acdea"
                    : ""))
        .applyTo(context);
    if (registerOidcClient) {
      context.registerBean(
          ReactiveClientRegistrationRepository.class,
          () -> new InMemoryReactiveClientRegistrationRepository(clientRegistration()));
      context.registerBean(
          WebSessionServerSecurityContextRepository.class,
          WebSessionServerSecurityContextRepository::new);
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
  private static class ProtectedProbeController {

    @GetMapping("/protected-probe")
    String probe() {
      return "protected";
    }
  }
}
