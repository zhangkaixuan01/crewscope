package io.crewscope.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NotificationIntentPolicyRegistryM6E04Test {

    @Test
    void fixedRegistryCoversFiveViewsButRejectsNotificationFailureRecursion() {
        NotificationIntentPolicyRegistry registry =
                CrewScopeNotificationIntentPolicies.fixedRegistry();

        assertEquals(5, registry.size());
        assertEquals("review-required", registry.find(
                InboxItemType.REVIEW, InboxSourceType.REVIEW_REQUEST)
                .orElseThrow().serverTemplateKey());
        assertTrue(registry.find(
                InboxItemType.EXCEPTION, InboxSourceType.NOTIFICATION_DELIVERY).isEmpty());
    }

    @Test
    void duplicateViewPolicyAndUnsafeValidityFailClosed() {
        NotificationIntentPolicy policy = new NotificationIntentPolicy(
                InboxItemType.REVIEW,
                Set.of(InboxSourceType.REVIEW_REQUEST),
                "review-required",
                1,
                Duration.ofHours(1));

        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationIntentPolicyRegistry(List.of(policy, policy)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationIntentPolicy(
                        InboxItemType.REVIEW,
                        Set.of(InboxSourceType.REVIEW_REQUEST),
                        "review-required",
                        1,
                        Duration.ofDays(2)));
    }
}
