package io.crewscope.application.principal;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Full-set, read-only Team subject directory query (M9b-A06).
 *
 * <p>Paging is dual-mode and the modes are mutually exclusive: offset keeps the legacy contract,
 * {@code cursor} is the stable keyset continuation. A by-id lookup is a point query, so it carries
 * no filter, no paging and no continuation.
 */
public record PrincipalDirectoryQuery(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<String> namePrefix,
        Set<PrincipalKind> types,
        Set<PrincipalId> ids,
        PrincipalDirectoryPurpose purpose,
        Optional<PrincipalDirectoryCursor> cursor,
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
        types = Set.copyOf(Objects.requireNonNull(types, "types"));
        ids = Set.copyOf(Objects.requireNonNull(ids, "ids"));
        if (ids.size() > 50) {
            throw new IllegalArgumentException("ids must name at most 50 principals");
        }
        purpose = Objects.requireNonNull(purpose, "purpose");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 200");
        }
        boolean pointQuery = !ids.isEmpty();
        if (pointQuery
                && (namePrefix.isPresent() || !types.isEmpty() || cursor.isPresent() || offset != 0)) {
            throw new IllegalArgumentException(
                    "A by-id lookup accepts no filter, no offset and no continuation");
        }
        if (cursor.isPresent() && offset != 0) {
            throw new IllegalArgumentException("offset and cursor are mutually exclusive");
        }
    }
}
