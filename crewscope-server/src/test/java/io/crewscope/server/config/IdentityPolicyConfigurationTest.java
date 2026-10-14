package io.crewscope.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.IdentityPolicy;
import org.junit.jupiter.api.Test;

/** Identity-policy vocabulary, credential gates and the startup mode matrix (M12-I01). */
class IdentityPolicyConfigurationTest {

  private final IdentityPolicyConfiguration configuration = new IdentityPolicyConfiguration();

  @Test
  void parsesTheConfiguredVocabulary() {
    assertEquals(IdentityPolicy.LOCAL_ONLY, IdentityPolicy.from("local-only"));
    assertEquals(IdentityPolicy.SSO_ONLY, IdentityPolicy.from(" SSO-ONLY "));
    assertEquals(IdentityPolicy.COEXIST, IdentityPolicy.from("coexist"));
  }

  @Test
  void rejectsBlankAndUnknownValuesInsteadOfDowngrading() {
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.from(null));
    assertThrows(IllegalArgumentException.class, () -> IdentityPolicy.from(" "));

    IllegalArgumentException unknown = assertThrows(
        IllegalArgumentException.class, () -> IdentityPolicy.from("federated"));
    assertEquals(
        "crewscope.security.identity-policy must be one of: local-only, sso-only, coexist",
        unknown.getMessage());
  }

  @Test
  void gatesLocalCredentialsOnThePolicyValue() {
    assertTrue(IdentityPolicy.LOCAL_ONLY.allowsLocalPasswordLogin());
    assertTrue(IdentityPolicy.LOCAL_ONLY.allowsLocalPasswordRegistration());
    assertFalse(IdentityPolicy.SSO_ONLY.allowsLocalPasswordLogin());
    assertFalse(IdentityPolicy.SSO_ONLY.allowsLocalPasswordRegistration());
    assertTrue(IdentityPolicy.COEXIST.allowsLocalPasswordLogin());
    assertTrue(IdentityPolicy.COEXIST.allowsLocalPasswordRegistration());
  }

  @Test
  void mirrorsTheConfiguredVocabularyBackToBrowsers() {
    assertEquals("local-only", IdentityPolicy.LOCAL_ONLY.configuredValue());
    assertEquals("sso-only", IdentityPolicy.SSO_ONLY.configuredValue());
    assertEquals("coexist", IdentityPolicy.COEXIST.configuredValue());
  }

  @Test
  void bootstrapDeploymentsStayCoexist() {
    assertEquals(
        IdentityPolicy.COEXIST, configuration.identityPolicy("coexist", "bootstrap"));

    assertThrows(
        IllegalStateException.class, () -> configuration.identityPolicy("local-only", "bootstrap"));
    assertThrows(
        IllegalStateException.class, () -> configuration.identityPolicy("sso-only", "bootstrap"));
  }

  @Test
  void localDeploymentsCannotPromiseSsoOnly() {
    assertEquals(IdentityPolicy.COEXIST, configuration.identityPolicy("coexist", "local"));
    assertEquals(
        IdentityPolicy.LOCAL_ONLY, configuration.identityPolicy("local-only", "local"));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> configuration.identityPolicy("sso-only", "local"));

    assertEquals(
        "crewscope.security.identity-policy cannot be sso-only while the deployment is in"
            + " local mode",
        failure.getMessage());
  }

  @Test
  void oidcDeploymentsCannotDisableTheirOnlyCredential() {
    assertEquals(IdentityPolicy.COEXIST, configuration.identityPolicy("coexist", "oidc"));
    assertEquals(IdentityPolicy.SSO_ONLY, configuration.identityPolicy("sso-only", "oidc"));

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> configuration.identityPolicy("local-only", "oidc"));

    assertEquals(
        "crewscope.security.identity-policy cannot be local-only while the deployment is in"
            + " oidc mode",
        failure.getMessage());
  }
}
