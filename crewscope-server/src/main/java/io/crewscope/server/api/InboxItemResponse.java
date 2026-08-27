package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxItemView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Reviewed public Inbox projection without member, Generation or internal projection fields. */
public record InboxItemResponse(
        UUID inboxItemId,
        String itemType,
        String priority,
        Instant deadline,
        Instant openedAt,
        String sourceStatus,
        String closeReason,
        Instant closedAt,
        String dispositionStatus,
        long dispositionVersion,
        String etag,
        SourceResponse source) {

    public static InboxItemResponse from(InboxItemView view) {
        InboxItemView value = Objects.requireNonNull(view, "view");
        var item = value.item();
        var source = item.source();
        var key = source.key();
        return new InboxItemResponse(
                item.id().value(),
                key.itemType().name(),
                source.priority().name(),
                source.deadline().map(timestamp -> timestamp.value()).orElse(null),
                source.openedAt().value(),
                source.status().name(),
                source.closeReason().map(Enum::name).orElse(null),
                source.closedAt().map(timestamp -> timestamp.value()).orElse(null),
                value.dispositionStatus().name(),
                value.dispositionVersion(),
                ApiHeaders.versionEtag(value.dispositionVersion()),
                new SourceResponse(
                        key.sourceType().name(), key.sourceId(), key.sourceRevision().value()));
    }

    public record SourceResponse(String type, UUID id, long revision) {
        public SourceResponse {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(id, "id");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
        }
    }
}
