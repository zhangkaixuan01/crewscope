package io.crewscope.application.activity;

import io.crewscope.domain.activity.ActivityReferenceType;
import java.util.Objects;

/** One explicitly reviewed public reference derived from a safe identity source. */
public record ActivityReferenceMapping(
        ActivityReferenceType type, ActivityIdentitySource source, boolean required) {

    public ActivityReferenceMapping {
        type = Objects.requireNonNull(type, "type");
        source = Objects.requireNonNull(source, "source");
    }

    public static ActivityReferenceMapping required(
            ActivityReferenceType type, ActivityIdentitySource source) {
        return new ActivityReferenceMapping(type, source, true);
    }

    public static ActivityReferenceMapping optional(
            ActivityReferenceType type, ActivityIdentitySource source) {
        return new ActivityReferenceMapping(type, source, false);
    }
}
