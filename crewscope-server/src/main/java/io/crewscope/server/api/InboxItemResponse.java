package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxItemView;
import io.crewscope.application.inbox.InboxSourceContext;
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
        SourceResponse source,
        SourceContextResponse sourceContext) {

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
                        key.sourceType().name(), key.sourceId(), key.sourceRevision().value()),
                value.sourceContext().map(SourceContextResponse::from).orElse(null));
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

    /** The readable work facts the row points at, joined at read time; null carries no context. */
    public record SourceContextResponse(
            UUID projectId,
            UUID workItemId,
            String workItemTitle,
            String taskObjective,
            String waitingOnDisplayName,
            String targetActionKind) {

        static SourceContextResponse from(InboxSourceContext context) {
            Objects.requireNonNull(context, "context");
            return new SourceContextResponse(
                    context.projectId().map(value -> value.value()).orElse(null),
                    context.workItemId().map(value -> value.value()).orElse(null),
                    context.workItemTitle().orElse(null),
                    context.taskObjective().orElse(null),
                    context.waitingOnDisplayName().orElse(null),
                    context.targetActionKind());
        }
    }
}
