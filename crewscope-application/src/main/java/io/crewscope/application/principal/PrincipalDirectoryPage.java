package io.crewscope.application.principal;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Offset page for the bounded Team subject directory. */
public record PrincipalDirectoryPage(List<PrincipalDirectoryEntry> items, OptionalInt nextOffset) {
    public PrincipalDirectoryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextOffset = Objects.requireNonNull(nextOffset, "nextOffset");
        nextOffset.ifPresent(value -> {
            if (value < 0) throw new IllegalArgumentException("nextOffset must not be negative");
        });
    }
}
