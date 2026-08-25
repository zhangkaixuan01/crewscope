package io.crewscope.domain.notification;

/** Monotonic published notification-template version. */
public record NotificationTemplateVersion(long value)
        implements Comparable<NotificationTemplateVersion> {

    public NotificationTemplateVersion {
        if (value < 1) {
            throw new IllegalArgumentException("NotificationTemplateVersion must be positive");
        }
    }

    @Override
    public int compareTo(NotificationTemplateVersion other) {
        return Long.compare(value, other.value);
    }
}
