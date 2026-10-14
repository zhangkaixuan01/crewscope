package io.crewscope.domain.identity;

import java.util.Locale;

/**
 * Which credential kinds may establish browser Sessions. The policy is an additive axis on top of
 * the deployment security mode, so a local-mode deployment can still declare {@code sso-only}
 * readiness only when the mode itself permits it; unsupported combinations fail startup.
 */
public enum IdentityPolicy {
  LOCAL_ONLY,
  SSO_ONLY,
  COEXIST;

  /** Parses the configured value; unsupported values fail startup instead of downgrading. */
  public static IdentityPolicy from(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("crewscope.security.identity-policy must not be blank");
    }
    try {
      return valueOf(value.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "crewscope.security.identity-policy must be one of: local-only, sso-only, coexist",
          exception);
    }
  }

  /** Local identifier+password login stays available unless the deployment is SSO-only. */
  public boolean allowsLocalPasswordLogin() {
    return this != SSO_ONLY;
  }

  /** Local registration stays available in local-only and coexist deployments. */
  public boolean allowsLocalPasswordRegistration() {
    return this != SSO_ONLY;
  }

  /** The configured vocabulary value, mirrored back by the Session projection. */
  public String configuredValue() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
