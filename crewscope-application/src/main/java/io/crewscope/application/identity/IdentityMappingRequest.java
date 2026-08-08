package io.crewscope.application.identity;

import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.UUID;

/** Trusted authentication facts used to resolve one external subject into a CrewScope Principal. */
public record IdentityMappingRequest(
    OrganizationId organizationId,
    ExternalIdentity externalIdentity,
    String displayName,
    UUID correlationId) {

  public IdentityMappingRequest {
    organizationId = Objects.requireNonNull(organizationId, "organizationId");
    externalIdentity = Objects.requireNonNull(externalIdentity, "externalIdentity");
    displayName = requireDisplayName(displayName);
    correlationId = Objects.requireNonNull(correlationId, "correlationId");
    if (AggregateId.NIL_UUID.equals(correlationId)) {
      throw new IllegalArgumentException("correlationId must not use the nil UUID");
    }
  }

  private static String requireDisplayName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    String normalized = value.strip();
    if (normalized.length() > Principal.MAX_DISPLAY_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "displayName must contain at most " + Principal.MAX_DISPLAY_NAME_LENGTH + " characters");
    }
    return normalized;
  }
}
