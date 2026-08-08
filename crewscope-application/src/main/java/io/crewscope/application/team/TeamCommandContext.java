package io.crewscope.application.team;

import io.crewscope.application.command.IdempotencyKey;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted identity plus retry and tracing metadata for one Team command. */
public record TeamCommandContext(
    TeamAccessContext access,
    IdempotencyKey idempotencyKey,
    UUID correlationId,
    Optional<UUID> causationId) {

  public TeamCommandContext {
    access = Objects.requireNonNull(access, "access");
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    correlationId = Objects.requireNonNull(correlationId, "correlationId");
    causationId = Objects.requireNonNull(causationId, "causationId");
  }
}
