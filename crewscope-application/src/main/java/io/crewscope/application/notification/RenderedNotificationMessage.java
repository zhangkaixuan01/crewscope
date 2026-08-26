package io.crewscope.application.notification;

/** Bounded server-rendered text accepted by a fixed collaboration Provider operation. */
public record RenderedNotificationMessage(String text) {

    private static final int MAXIMUM_TEXT_LENGTH = 4_000;

    public RenderedNotificationMessage {
        if (text == null || text.isBlank() || text.length() > MAXIMUM_TEXT_LENGTH
                || text.codePoints().anyMatch(RenderedNotificationMessage::forbiddenControl)) {
            throw new IllegalArgumentException(
                    "Rendered notification text must be non-blank, bounded and control-free");
        }
    }

    private static boolean forbiddenControl(int codePoint) {
        return codePoint != '\n' && (Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT);
    }

    @Override
    public String toString() {
        return "RenderedNotificationMessage[text=REDACTED]";
    }
}
