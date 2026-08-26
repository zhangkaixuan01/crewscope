package io.crewscope.application.activity;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reviewed source used to derive a public Activity Subject or Reference identity. */
public record ActivityIdentitySource(Kind kind, Optional<String> payloadPath) {

    private static final Pattern PAYLOAD_PATH =
            Pattern.compile("[a-z][A-Za-z0-9]*(?:\\.[a-z][A-Za-z0-9]*)*");

    public ActivityIdentitySource {
        kind = Objects.requireNonNull(kind, "kind");
        payloadPath = Objects.requireNonNull(payloadPath, "payloadPath").map(String::strip);
        if ((kind == Kind.PAYLOAD) != payloadPath.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a PAYLOAD Activity identity source carries a payloadPath");
        }
        payloadPath.ifPresent(path -> {
            if (!PAYLOAD_PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("Activity identity payloadPath is invalid");
            }
        });
    }

    public static ActivityIdentitySource team() {
        return new ActivityIdentitySource(Kind.TEAM, Optional.empty());
    }

    public static ActivityIdentitySource aggregate() {
        return new ActivityIdentitySource(Kind.AGGREGATE, Optional.empty());
    }

    public static ActivityIdentitySource payload(String path) {
        return new ActivityIdentitySource(Kind.PAYLOAD, Optional.of(path));
    }

    public enum Kind {
        TEAM,
        AGGREGATE,
        PAYLOAD
    }
}
