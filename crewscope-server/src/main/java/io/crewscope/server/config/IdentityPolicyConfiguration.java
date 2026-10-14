package io.crewscope.server.config;

import io.crewscope.domain.identity.IdentityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Parses the identity policy once and enforces the mode matrix at startup: bootstrap deployments
 * stay coexist, local deployments cannot promise SSO-only logins, and OIDC deployments cannot
 * disable the only credential they have.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityPolicyConfiguration {

  @Bean
  IdentityPolicy identityPolicy(
      @Value("${crewscope.security.identity-policy:coexist}") String configuredPolicy,
      @Value("${crewscope.security.mode:bootstrap}") String configuredMode) {
    SecurityMode mode = SecurityMode.from(configuredMode);
    IdentityPolicy policy = IdentityPolicy.from(configuredPolicy);
    if (mode == SecurityMode.BOOTSTRAP && policy != IdentityPolicy.COEXIST) {
      throw new IllegalStateException(
          "crewscope.security.identity-policy must stay coexist while the deployment is in"
              + " bootstrap mode");
    }
    if (mode == SecurityMode.LOCAL && policy == IdentityPolicy.SSO_ONLY) {
      throw new IllegalStateException(
          "crewscope.security.identity-policy cannot be sso-only while the deployment is in"
              + " local mode");
    }
    if (mode == SecurityMode.OIDC && policy == IdentityPolicy.LOCAL_ONLY) {
      throw new IllegalStateException(
          "crewscope.security.identity-policy cannot be local-only while the deployment is in"
              + " oidc mode");
    }
    return policy;
  }
}
