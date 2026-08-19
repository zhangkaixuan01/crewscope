package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** SHA-256 proof closing a Docker Sandbox against Workspace, Policy and ownership facts. */
public record TaskExecutionSandboxFingerprint(String value) {

    public TaskExecutionSandboxFingerprint {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "TaskExecution Sandbox fingerprint must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
