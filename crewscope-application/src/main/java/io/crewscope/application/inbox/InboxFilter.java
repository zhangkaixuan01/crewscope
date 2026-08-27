package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceStatus;
import java.util.Objects;
import java.util.Set;

/** Bounded, closed-enum filter for the five member Inbox views. */
public record InboxFilter(
        Set<InboxItemType> itemTypes,
        Set<InboxSourceStatus> sourceStatuses,
        Set<InboxDispositionStatus> dispositionStatuses) {

    public static final InboxFilter OPEN = new InboxFilter(
            Set.of(), Set.of(InboxSourceStatus.OPEN), Set.of());

    public InboxFilter {
        itemTypes = Set.copyOf(Objects.requireNonNull(itemTypes, "itemTypes"));
        sourceStatuses = Set.copyOf(Objects.requireNonNull(sourceStatuses, "sourceStatuses"));
        dispositionStatuses =
                Set.copyOf(Objects.requireNonNull(dispositionStatuses, "dispositionStatuses"));
    }
}
