package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxItemType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete five-category count snapshot for one active Team member. */
public record InboxCounts(Map<InboxItemType, InboxTypeCount> byType) {

    public InboxCounts {
        Objects.requireNonNull(byType, "byType");
        EnumMap<InboxItemType, InboxTypeCount> normalized = new EnumMap<>(InboxItemType.class);
        normalized.putAll(byType);
        for (InboxItemType type : InboxItemType.values()) {
            normalized.putIfAbsent(type, new InboxTypeCount(0, 0));
        }
        byType = Map.copyOf(normalized);
    }

    public long total() {
        return byType.values().stream().mapToLong(InboxTypeCount::total).sum();
    }

    public long unread() {
        return byType.values().stream().mapToLong(InboxTypeCount::unread).sum();
    }
}
