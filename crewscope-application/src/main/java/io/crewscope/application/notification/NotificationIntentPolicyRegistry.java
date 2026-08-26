package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable fixed-template policy registry; unregistered source families cannot notify. */
public final class NotificationIntentPolicyRegistry {

    private final Map<InboxItemType, NotificationIntentPolicy> policies;

    public NotificationIntentPolicyRegistry(List<NotificationIntentPolicy> policies) {
        EnumMap<InboxItemType, NotificationIntentPolicy> registered =
                new EnumMap<>(InboxItemType.class);
        for (NotificationIntentPolicy policy : List.copyOf(
                Objects.requireNonNull(policies, "policies"))) {
            NotificationIntentPolicy value = Objects.requireNonNull(policy, "policy");
            if (registered.putIfAbsent(value.itemType(), value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Notification policy for " + value.itemType());
            }
        }
        this.policies = Map.copyOf(registered);
    }

    public Optional<NotificationIntentPolicy> find(
            InboxItemType itemType, InboxSourceType sourceType) {
        NotificationIntentPolicy policy = policies.get(
                Objects.requireNonNull(itemType, "itemType"));
        return policy != null && policy.supports(sourceType)
                ? Optional.of(policy)
                : Optional.empty();
    }

    public int size() {
        return policies.size();
    }
}
