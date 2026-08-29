package io.crewscope.server.config;

import java.util.Locale;

/** Explicit authentication profile; unsupported values fail startup instead of downgrading. */
public enum SecurityMode {
  BOOTSTRAP,
  LOCAL,
  OIDC;

  public static SecurityMode from(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("crewscope.security.mode must not be blank");
    }
    try {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "crewscope.security.mode must be one of: bootstrap, local, oidc", exception);
    }
  }
}
