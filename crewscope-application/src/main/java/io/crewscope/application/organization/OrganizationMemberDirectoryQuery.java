package io.crewscope.application.organization;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/**
 * Full-set, read-only organization member directory query (M13b-A01).
 *
 * <p>Paging is dual-mode and the modes are mutually exclusive: {@code offset} keeps the legacy
 * contract, {@code cursor} is the stable keyset continuation. The candidate set is fixed — this
 * organization's ACTIVE account bindings — so a query carries no other set-shaping parameter
 * beyond the display-name/username prefix.
 */
public record OrganizationMemberDirectoryQuery(
        OrganizationId organizationId,
        Optional<String> namePrefix,
        Optional<OrganizationMemberDirectoryCursor> cursor,
        int offset,
        int limit) {

    public OrganizationMemberDirectoryQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        namePrefix = Objects.requireNonNull(namePrefix, "namePrefix")
                .map(String::strip).filter(value -> !value.isEmpty());
        namePrefix.ifPresent(value -> {
            if (value.length() > 100) {
                throw new IllegalArgumentException("namePrefix must not exceed 100 characters");
            }
        });
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("offset must be non-negative and limit between 1 and 200");
        }
        if (cursor.isPresent() && offset != 0) {
            throw new IllegalArgumentException("offset and cursor are mutually exclusive");
        }
    }
}
