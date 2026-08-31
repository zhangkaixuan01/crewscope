package io.crewscope.application.model;

import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;

/** Ensures the platform-owned model Provider, catalog and price facts are available. */
@FunctionalInterface
public interface PlatformModelCatalogInitializer {

  /** Idempotently restores missing built-in model facts without storing a credential. */
  void initialize(PrincipalId actor, UtcTimestamp occurredAt);
}
