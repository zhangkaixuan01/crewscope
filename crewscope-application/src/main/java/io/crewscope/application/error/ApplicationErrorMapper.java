package io.crewscope.application.error;

import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainException;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts safe domain errors from direct or infrastructure-wrapped failures.
 *
 * <p>Unknown failures return an empty result so server adapters can emit a generic internal error
 * without exposing exception messages or implementation details.
 */
public final class ApplicationErrorMapper {

    private ApplicationErrorMapper() {}

    public static Optional<DomainError> from(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof DomainException domainException) {
                return Optional.of(domainException.error());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
