package io.crewscope.application.collaboration;

import java.util.Objects;

/** Reports a normalized failed Lark Preflight without retaining remote identity or payload. */
public final class LarkConnectionPreflightException extends RuntimeException {

    private final LarkProviderHealth health;

    public LarkConnectionPreflightException(LarkProviderHealth health) {
        super("Lark Connection Preflight failed: "
                + Objects.requireNonNull(health, "health").status());
        this.health = health;
    }

    public LarkProviderHealth health() {
        return health;
    }
}
