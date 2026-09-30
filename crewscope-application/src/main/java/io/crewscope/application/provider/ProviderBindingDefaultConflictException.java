package io.crewscope.application.provider;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports that a default Provider Binding already exists at the target level.
 *
 * <p>The {@code ux_provider_binding_active_default} partial unique index enforces at most one
 * ACTIVE default binding per exact level; a second creation used to leak the constraint as a
 * retryable internal error with no pointer to the existing binding (M9b-Q02 defect 23). The
 * conflict carries the existing binding id so clients can act — demote the current default or
 * replace it — and maps to 409 through the standard {@link DomainException} channel.
 */
public final class ProviderBindingDefaultConflictException extends DomainException {

  private static final String CONSTRAINT_NAME = "ux_provider_binding_active_default";

  public ProviderBindingDefaultConflictException(ProviderBindingId existingBindingId) {
    super(new DomainError(
        DomainErrorCode.PROVIDER_BINDING_DEFAULT_CONFLICT,
        "A default Provider Binding already exists at this level",
        Map.of("existingBindingId",
            Objects.requireNonNull(existingBindingId, "existingBindingId").toString())));
  }

  /**
   * Concurrent-insert fallback: the level check passed but a parallel writer committed the
   * default binding first. Walks the failure cause chain for the PostgreSQL 23505 rejection
   * of this exact constraint — translated or raw Hibernate shape alike — and returns empty
   * for every other integrity failure so it can be rethrown unchanged.
   */
  public static Optional<ProviderBindingDefaultConflictException> fromConstraintViolation(
      Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof java.sql.SQLException sql
          && "23505".equals(sql.getSQLState())
          && sql.getMessage() != null
          && sql.getMessage().contains(CONSTRAINT_NAME)) {
        return Optional.of(new ProviderBindingDefaultConflictException());
      }
      current = current.getCause();
    }
    return Optional.empty();
  }

  private ProviderBindingDefaultConflictException() {
    super(new DomainError(
        DomainErrorCode.PROVIDER_BINDING_DEFAULT_CONFLICT,
        "A default Provider Binding already exists at this level",
        Map.of("constraint", CONSTRAINT_NAME)));
  }
}
