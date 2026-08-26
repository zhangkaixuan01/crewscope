package io.crewscope.application.notification;

import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxSourceType;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Fixed server policy selecting one published template for an eligible Inbox source family. */
public record NotificationIntentPolicy(
        InboxItemType itemType,
        Set<InboxSourceType> sourceTypes,
        String serverTemplateKey,
        long policyVersion,
        Duration actionValidity) {

    public NotificationIntentPolicy {
        itemType = Objects.requireNonNull(itemType, "itemType");
        sourceTypes = Set.copyOf(Objects.requireNonNull(sourceTypes, "sourceTypes"));
        if (sourceTypes.isEmpty()) {
            throw new IllegalArgumentException("Notification policy sourceTypes must not be empty");
        }
        if (serverTemplateKey == null
                || !serverTemplateKey.matches("[a-z][a-z0-9._-]{2,127}")) {
            throw new IllegalArgumentException(
                    "Notification policy serverTemplateKey must be a stable registry key");
        }
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("Notification policyVersion must be positive");
        }
        actionValidity = Objects.requireNonNull(actionValidity, "actionValidity");
        if (actionValidity.compareTo(Duration.ofMinutes(1)) < 0
                || actionValidity.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "Notification actionValidity must be between 1 minute and 24 hours");
        }
    }

    public boolean supports(InboxSourceType sourceType) {
        return sourceTypes.contains(Objects.requireNonNull(sourceType, "sourceType"));
    }
}
