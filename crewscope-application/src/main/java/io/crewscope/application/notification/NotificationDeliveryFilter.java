package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded low-cardinality filters for administrator delivery history. */
public record NotificationDeliveryFilter(
        Set<NotificationDeliveryStatus> statuses,
        Set<InboxItemType> itemTypes,
        Optional<TeamMemberId> recipientMemberId) {

    public static final NotificationDeliveryFilter ALL =
            new NotificationDeliveryFilter(Set.of(), Set.of(), Optional.empty());

    public NotificationDeliveryFilter {
        statuses = bounded(statuses, "statuses");
        itemTypes = bounded(itemTypes, "itemTypes");
        recipientMemberId = Objects.requireNonNull(recipientMemberId, "recipientMemberId");
    }

    private static <T> Set<T> bounded(Set<T> values, String name) {
        Set<T> copy = Set.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > 20) {
            throw new IllegalArgumentException(name + " must contain at most 20 values");
        }
        return copy;
    }
}
