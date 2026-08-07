package io.crewscope.domain.shared.event;

import java.util.regex.Pattern;

/** Stable upper-snake event name shared by persistence, Outbox and realtime contracts. */
public record EventType(String value) {

    public static final int MAX_LENGTH = 200;
    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]*");

    public EventType {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EventType must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "EventType must be upper snake case and contain at most " + MAX_LENGTH + " characters");
        }
    }

    public static EventType from(String value) {
        return new EventType(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
