package io.crewscope.application.principal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One page of the Team subject directory in the stable (sort key, principal id) ordering.
 *
 * <p>Exactly one continuation is present when the page truncates: the legacy {@code nextOffset}
 * for an offset query, or the signed-cursor tail {@code nextCursor} for a keyset query.
 */
public record PrincipalDirectoryPage(
        List<PrincipalDirectoryEntry> items,
        OptionalInt nextOffset,
        Optional<PrincipalDirectoryCursor> nextCursor) {

    public PrincipalDirectoryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextOffset = Objects.requireNonNull(nextOffset, "nextOffset");
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        nextOffset.ifPresent(value -> {
            if (value < 0) throw new IllegalArgumentException("nextOffset must not be negative");
        });
    }
}
