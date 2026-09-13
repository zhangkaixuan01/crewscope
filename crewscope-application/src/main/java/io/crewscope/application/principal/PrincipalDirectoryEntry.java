package io.crewscope.application.principal;

import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.shared.id.PrincipalId;
import java.util.List;
import java.util.Objects;

/** Member-safe subject projection; contact and lifecycle internals are intentionally omitted. */
public record PrincipalDirectoryEntry(
        PrincipalId principalId,
        PrincipalKind kind,
        String displayName,
        PrincipalStatus status,
        List<String> roles) {

    public PrincipalDirectoryEntry {
        principalId = Objects.requireNonNull(principalId, "principalId");
        kind = Objects.requireNonNull(kind, "kind");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.strip();
        status = Objects.requireNonNull(status, "status");
        roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
