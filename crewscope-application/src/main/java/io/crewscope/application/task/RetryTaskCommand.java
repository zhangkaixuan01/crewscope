package io.crewscope.application.task;

import io.crewscope.domain.agent.AgentConfigurationRevision;
import java.util.Objects;
import java.util.Optional;

/** Strong-version precondition for retrying the current failed attempt. */
public record RetryTaskCommand(
        long expectedExecutionVersion,
        Optional<AgentConfigurationRevision> configurationRevision) {

    public RetryTaskCommand {
        if (expectedExecutionVersion < 0) {
            throw new IllegalArgumentException("expectedExecutionVersion must not be negative");
        }
        configurationRevision = Objects.requireNonNull(configurationRevision, "configurationRevision");
    }

    public RetryTaskCommand(long expectedExecutionVersion) {
        this(expectedExecutionVersion, Optional.empty());
    }
}
