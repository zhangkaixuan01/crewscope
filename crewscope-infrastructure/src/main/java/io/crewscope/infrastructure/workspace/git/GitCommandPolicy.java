package io.crewscope.infrastructure.workspace.git;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Fixed process isolation and resource limits for host Git management commands. */
public record GitCommandPolicy(Path commandHome, Duration timeout, int maximumOutputBytes) {

    public static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(5);
    public static final int MINIMUM_OUTPUT_BYTES = 1_024;
    public static final int MAXIMUM_OUTPUT_BYTES = 16 * 1_024 * 1_024;

    public GitCommandPolicy {
        commandHome = Objects.requireNonNull(commandHome, "commandHome").toAbsolutePath().normalize();
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Git command timeout must be within (0, 5m]");
        }
        if (maximumOutputBytes < MINIMUM_OUTPUT_BYTES
                || maximumOutputBytes > MAXIMUM_OUTPUT_BYTES) {
            throw new IllegalArgumentException("Git output limit must be between 1 KiB and 16 MiB");
        }
    }
}
