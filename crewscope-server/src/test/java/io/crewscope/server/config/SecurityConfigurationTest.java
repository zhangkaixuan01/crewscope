package io.crewscope.server.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.server.SecurityWebFilterChain;

class SecurityConfigurationTest {

  @Test
  void buildsTheBootstrapProfile() {
    try (var context = context("bootstrap", false)) {
      assertNotNull(context.getBean(SecurityWebFilterChain.class));
    }
  }

  @Test
  void buildsTheOidcProfileWhenAClientRegistrationExists() {
    try (var context = context("oidc", true)) {
      assertNotNull(context.getBean(SecurityWebFilterChain.class));
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
            "crewscope.security.oidc.organization-id="
                + (mode.equals("oidc") && bindOidcOrganization
                    ? "d3ff4c9c-7a93-4fc7-91ac-4e3f328acdea"
                    : ""))
        .applyTo(context);
    if (registerOidcClient) {
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
}
