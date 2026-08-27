package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxCounts;
import io.crewscope.application.inbox.InboxTypeCount;
import io.crewscope.domain.inbox.InboxItemType;
import java.util.Map;

/** Complete five-category member Inbox badge snapshot. */
public record InboxCountsResponse(
        long total, long unread, Map<String, CountResponse> byType) {

    static InboxCountsResponse from(InboxCounts counts) {
        Map<String, CountResponse> values = new java.util.LinkedHashMap<>();
        for (InboxItemType type : InboxItemType.values()) {
            InboxTypeCount count = counts.byType().get(type);
            values.put(type.name(), new CountResponse(count.total(), count.unread()));
        }
        return new InboxCountsResponse(counts.total(), counts.unread(), Map.copyOf(values));
    }

    public record CountResponse(long total, long unread) {}
}
