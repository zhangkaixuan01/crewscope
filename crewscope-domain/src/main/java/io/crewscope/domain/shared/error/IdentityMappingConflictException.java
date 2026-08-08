package io.crewscope.domain.shared.error;

import java.util.Map;

/** Reports that an external identity is already bound to an incompatible Principal shape. */
public final class IdentityMappingConflictException extends DomainException {

  public IdentityMappingConflictException(String provider) {
    super(
        new DomainError(
            DomainErrorCode.IDENTITY_MAPPING_CONFLICT,
            "External identity is already bound to an incompatible Principal",
            Map.of("provider", requireProvider(provider))));
  }

  private static String requireProvider(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("provider must not be blank");
    }
    return value.strip();
  }
}
