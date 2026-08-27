package io.crewscope.application.inbox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stable Keyset page from one current member-inbox generation. */
public record InboxPage(List<InboxItemView> items, Optional<InboxCursor> nextCursor) {

    public InboxPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
