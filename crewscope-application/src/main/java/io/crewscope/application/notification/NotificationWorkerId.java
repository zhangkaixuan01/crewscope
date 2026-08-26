package io.crewscope.application.notification;

/** Bounded, non-secret identity used to fence one Notification Worker process. */
public record NotificationWorkerId(String value) {

    public NotificationWorkerId {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException(
                    "Notification Worker ID must contain 1 to 160 characters");
        }
        value = value.strip();
    }

    @Override
    public String toString() {
        return value;
    }
}
