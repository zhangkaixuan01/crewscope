package io.crewscope.application.principal;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/** Bounded, read-only Team subject directory query. */
public record PrincipalDirectoryQuery(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<String> namePrefix,
        int offset,
        int limit) {

    public PrincipalDirectoryQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        namePrefix = Objects.requireNonNull(namePrefix, "namePrefix")
                .map(String::strip).filter(value -> !value.isEmpty());
        namePrefix.ifPresent(value -> {
            if (value.length() > 100) {
                throw new IllegalArgumentException("namePrefix must not exceed 100 characters");
            }
        });
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 200");
        }
    }
}
