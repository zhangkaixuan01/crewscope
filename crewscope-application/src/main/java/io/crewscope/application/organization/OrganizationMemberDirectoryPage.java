package io.crewscope.application.organization;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One page of the organization member directory in the stable (sort key, principal id) ordering.
 *
 * <p>Exactly one continuation is present when the page truncates: the legacy {@code nextOffset}
 * for an offset query, or the signed-cursor tail {@code nextCursor} for a keyset query.
 */
public record OrganizationMemberDirectoryPage(
        List<OrganizationMemberDirectoryEntry> items,
        OptionalInt nextOffset,
        Optional<OrganizationMemberDirectoryCursor> nextCursor) {

    public OrganizationMemberDirectoryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextOffset = Objects.requireNonNull(nextOffset, "nextOffset");
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        nextOffset.ifPresent(value -> {
            if (value < 0) throw new IllegalArgumentException("nextOffset must not be negative");
        });
    }
}
