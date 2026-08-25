package io.crewscope.domain.notification;

/** First drift coordinate that invalidates a previously planned notification. */
public enum NotificationInvalidationReason {
    SOURCE,
    TEMPLATE,
    VARIABLES,
    RECIPIENT_MAPPING,
    PROVIDER_BINDING,
    CONNECTION,
    GRANT,
    TEAM_POLICY,
    MEMBER_PREFERENCE
}
