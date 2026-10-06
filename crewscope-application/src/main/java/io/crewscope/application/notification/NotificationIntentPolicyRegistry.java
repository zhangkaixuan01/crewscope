package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable fixed-template policy registry; unregistered source families cannot notify.
 * One item type may carry several policies as long as their source-type families stay
 * pairwise disjoint — {@link #find(InboxItemType, InboxSourceType)} resolves the pair.
 */
public final class NotificationIntentPolicyRegistry {

    private final Map<InboxItemType, List<NotificationIntentPolicy>> policies;
    private final int policyCount;

    public NotificationIntentPolicyRegistry(List<NotificationIntentPolicy> policies) {
        EnumMap<InboxItemType, List<NotificationIntentPolicy>> registered =
                new EnumMap<>(InboxItemType.class);
        int count = 0;
        for (NotificationIntentPolicy policy : List.copyOf(
                Objects.requireNonNull(policies, "policies"))) {
            NotificationIntentPolicy value = Objects.requireNonNull(policy, "policy");
            for (NotificationIntentPolicy existing : registered.getOrDefault(
                    value.itemType(), List.of())) {
                if (!Collections.disjoint(existing.sourceTypes(), value.sourceTypes())) {
                    throw new IllegalArgumentException(
                            "Duplicate Notification policy for " + value.itemType());
                }
            }
            registered.computeIfAbsent(value.itemType(), ignored -> new ArrayList<>())
                    .add(value);
            count++;
        }
        EnumMap<InboxItemType, List<NotificationIntentPolicy>> frozen =
                new EnumMap<>(InboxItemType.class);
        registered.forEach((type, values) -> frozen.put(type, List.copyOf(values)));
        this.policies = Collections.unmodifiableMap(frozen);
        this.policyCount = count;
    }

    public Optional<NotificationIntentPolicy> find(
            InboxItemType itemType, InboxSourceType sourceType) {
        for (NotificationIntentPolicy policy : policies.getOrDefault(
                Objects.requireNonNull(itemType, "itemType"), List.of())) {
            if (policy.supports(sourceType)) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return policyCount;
    }
}
