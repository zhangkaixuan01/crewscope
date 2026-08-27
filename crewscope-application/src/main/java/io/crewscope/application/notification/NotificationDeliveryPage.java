package io.crewscope.application.notification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded stable delivery-history page. */
public record NotificationDeliveryPage(
        List<NotificationDeliveryView> items,
        Optional<NotificationDeliveryCursor> nextCursor) {

    public NotificationDeliveryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        if (items.isEmpty() && nextCursor.isPresent()) {
            throw new IllegalArgumentException("Empty notification page cannot have a Cursor");
        }
    }
}
