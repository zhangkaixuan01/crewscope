package io.crewscope.domain.identity.event;

import io.crewscope.domain.shared.DomainEvent;

/** Version 1 fact emitted when an external subject first receives a CrewScope USER Principal. */
public record UserIdentityMapped(String provider) implements DomainEvent {

  public UserIdentityMapped {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider must not be blank");
    }
    provider = provider.strip();
    if (provider.length() > io.crewscope.domain.identity.ExternalIdentity.MAX_PROVIDER_LENGTH) {
      throw new IllegalArgumentException(
          "provider must contain at most "
              + io.crewscope.domain.identity.ExternalIdentity.MAX_PROVIDER_LENGTH
              + " characters");
    }
  }
}
