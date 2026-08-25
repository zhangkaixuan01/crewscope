package io.crewscope.domain.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Versioned member preference evaluated before every automatic plan or redelivery. */
public record NotificationPreference(
        TeamMemberId memberId,
        boolean enabled,
        Set<InboxItemType> enabledItemTypes,
        Optional<UtcTimestamp> mutedUntil,
        long version) {

    public NotificationPreference {
        memberId = Objects.requireNonNull(memberId, "memberId");
        enabledItemTypes = Set.copyOf(Objects.requireNonNull(enabledItemTypes, "enabledItemTypes"));
        mutedUntil = Objects.requireNonNull(mutedUntil, "mutedUntil");
        if (version < 0) {
            throw new DomainValidationException("notificationPreference.version", "must not be negative");
        }
    }

    public NotificationPreferenceDecision decide(InboxItemType itemType, UtcTimestamp now) {
        Objects.requireNonNull(itemType, "itemType");
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (!enabled || !enabledItemTypes.contains(itemType)) {
            return NotificationPreferenceDecision.DENIED;
        }
        return mutedUntil.filter(until -> until.compareTo(requiredNow) > 0).isPresent()
                ? NotificationPreferenceDecision.DEFERRED
                : NotificationPreferenceDecision.ALLOWED;
    }
}
