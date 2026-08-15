package io.crewscope.server.config.runtime;

import io.crewscope.domain.runtime.RuntimeProfile;
import java.util.Locale;

/** Process topology selection; only worker-capable values map to a domain RuntimeProfile. */
public enum RuntimeDeploymentProfile {
    SERVER,
    ALL,
    WORKER;

    public static RuntimeDeploymentProfile parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("crewscope.runtime.execution-profile must not be blank");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "crewscope.runtime.execution-profile must be server, all or worker",
                    exception);
        }
    }

    public boolean workerCapable() {
        return this == ALL || this == WORKER;
    }

    public RuntimeProfile workerProfile() {
        return switch (this) {
            case ALL -> RuntimeProfile.ALL;
            case WORKER -> RuntimeProfile.WORKER;
            case SERVER -> throw new IllegalStateException("server Profile does not own a Worker");
        };
    }
}
