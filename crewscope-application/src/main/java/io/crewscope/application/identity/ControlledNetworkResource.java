package io.crewscope.application.identity;

import java.util.Objects;

/** Ephemeral canonical network prefix selected by the trusted request-boundary resolver. */
public final class ControlledNetworkResource {

    private final String canonicalValue;

    private ControlledNetworkResource(String canonicalValue) {
        this.canonicalValue = canonicalValue;
    }

    public static ControlledNetworkResource ofCanonical(String canonicalValue) {
        String required = Objects.requireNonNull(canonicalValue, "canonicalValue").strip();
        if (required.isEmpty()
                || required.length() > 96
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Controlled network coordinate is invalid");
        }
        return new ControlledNetworkResource(required);
    }

    /** Trusted HMAC adapters must never log or persist this preimage. */
    public String canonicalValue() {
        return canonicalValue;
    }

    @Override
    public String toString() {
        return "ControlledNetworkResource[REDACTED]";
    }
}
