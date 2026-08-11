package io.crewscope.application.provider;

import java.util.Objects;

/** Native Provider capability request and its current fail-closed Binding resolution. */
public record ProviderBindingLookup(
    BuiltInProviderRegistration registration, ProviderBindingResolution resolution) {

  public ProviderBindingLookup {
    registration = Objects.requireNonNull(registration, "registration");
    resolution = Objects.requireNonNull(resolution, "resolution");
  }
}
