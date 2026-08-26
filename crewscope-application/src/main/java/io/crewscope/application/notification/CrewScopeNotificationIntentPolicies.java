package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceType;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Product-owned MVP policy matrix for fixed-template member notifications. */
public final class CrewScopeNotificationIntentPolicies {

    public static final long POLICY_VERSION = 1;
    public static final Duration ACTION_VALIDITY = Duration.ofHours(1);

    private CrewScopeNotificationIntentPolicies() {}

    public static NotificationIntentPolicyRegistry fixedRegistry() {
        return new NotificationIntentPolicyRegistry(List.of(
                policy(InboxItemType.OWNERSHIP, "ownership-assigned",
                        InboxSourceType.RESPONSIBILITY_ASSIGNMENT),
                policy(InboxItemType.EXECUTION, "execution-assigned",
                        InboxSourceType.RESPONSIBILITY_ASSIGNMENT),
                policy(InboxItemType.REVIEW, "review-required",
                        InboxSourceType.REVIEW_REQUEST),
                policy(InboxItemType.CONFIRMATION, "confirmation-required",
                        InboxSourceType.ACTION_CONFIRMATION),
                policy(InboxItemType.EXCEPTION, "exception-alert",
                        InboxSourceType.TASK_EXECUTION, InboxSourceType.ACTION_DELIVERY)));
    }

    private static NotificationIntentPolicy policy(
            InboxItemType itemType, String templateKey, InboxSourceType... sourceTypes) {
        return new NotificationIntentPolicy(
                itemType, Set.of(sourceTypes), templateKey, POLICY_VERSION, ACTION_VALIDITY);
    }
}
