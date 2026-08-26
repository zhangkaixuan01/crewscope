package io.crewscope.application.collaboration;

import io.crewscope.domain.collaboration.LarkMemberMapping;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded mapping page with a stable server-side keyset Cursor. */
public record LarkMemberMappingPage(
        List<LarkMemberMapping> items, Optional<LarkMemberMappingCursor> nextCursor) {

    public LarkMemberMappingPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (items.isEmpty() && nextCursor.isPresent()) {
            throw new IllegalArgumentException("Empty Lark mapping page cannot have a Cursor");
        }
    }
}
