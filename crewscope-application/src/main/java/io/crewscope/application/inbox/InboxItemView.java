package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItem;
import java.util.Objects;
import java.util.Optional;

/** Current generation source merged server-side with generation-independent member authority. */
public record InboxItemView(
        InboxItem item,
        InboxDispositionStatus dispositionStatus,
        long dispositionVersion,
        Optional<InboxSourceContext> sourceContext) {

    public InboxItemView {
        item = Objects.requireNonNull(item, "item");
        dispositionStatus = Objects.requireNonNull(dispositionStatus, "dispositionStatus");
        sourceContext = Objects.requireNonNull(sourceContext, "sourceContext");
        if (dispositionVersion < 0) {
            throw new IllegalArgumentException("dispositionVersion must not be negative");
        }
        if ((dispositionStatus == InboxDispositionStatus.UNREAD) != (dispositionVersion == 0)) {
            throw new IllegalArgumentException(
                    "UNREAD must use version 0 and persisted dispositions must be positive");
        }
    }

    public static InboxItemView merge(
            InboxItem item, Optional<InboxDisposition> disposition) {
        return merge(item, disposition, Optional.empty());
    }

    public static InboxItemView merge(
            InboxItem item,
            Optional<InboxDisposition> disposition,
            Optional<InboxSourceContext> sourceContext) {
        InboxItem requiredItem = Objects.requireNonNull(item, "item");
        Optional<InboxDisposition> requiredDisposition =
                Objects.requireNonNull(disposition, "disposition");
        Optional<InboxSourceContext> requiredContext =
                Objects.requireNonNull(sourceContext, "sourceContext");
        if (requiredDisposition.isEmpty()) {
            return new InboxItemView(
                    requiredItem, InboxDispositionStatus.UNREAD, 0, requiredContext);
        }
        InboxDisposition value = requiredDisposition.orElseThrow();
        if (!value.belongsTo(requiredItem)) {
            throw new IllegalArgumentException(
                    "Inbox disposition does not belong to the projected Inbox item");
        }
        return new InboxItemView(
                requiredItem, value.status(), value.version(), requiredContext);
    }
}
