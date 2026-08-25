package io.crewscope.domain.inbox;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Stable reason explaining why a projected Inbox source no longer needs handling. */
public enum InboxCloseReason {
    RESPONSIBILITY_RELEASED(EnumSet.of(InboxItemType.OWNERSHIP, InboxItemType.EXECUTION)),
    RESPONSIBILITY_REPLACED(EnumSet.of(InboxItemType.OWNERSHIP, InboxItemType.EXECUTION)),
    REVIEW_COMPLETED(EnumSet.of(InboxItemType.REVIEW)),
    REVIEW_SUPERSEDED(EnumSet.of(InboxItemType.REVIEW)),
    CONFIRMATION_COMPLETED(EnumSet.of(InboxItemType.CONFIRMATION)),
    CONFIRMATION_CANCELLED(EnumSet.of(InboxItemType.CONFIRMATION)),
    CONFIRMATION_EXPIRED(EnumSet.of(InboxItemType.CONFIRMATION)),
    EXCEPTION_RECOVERED(EnumSet.of(InboxItemType.EXCEPTION)),
    EXCEPTION_RESOLVED(EnumSet.of(InboxItemType.EXCEPTION)),
    MEMBER_NO_LONGER_ELIGIBLE(EnumSet.allOf(InboxItemType.class));

    private final Set<InboxItemType> supportedItemTypes;

    InboxCloseReason(Set<InboxItemType> supportedItemTypes) {
        this.supportedItemTypes = Set.copyOf(supportedItemTypes);
    }

    public boolean supports(InboxItemType itemType) {
        return supportedItemTypes.contains(Objects.requireNonNull(itemType, "itemType"));
    }
}
