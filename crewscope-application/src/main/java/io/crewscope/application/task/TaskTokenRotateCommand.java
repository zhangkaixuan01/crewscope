package io.crewscope.application.task;

import io.crewscope.domain.task.TaskProviderGrantRequest;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/** Replaces one live token with a strictly equal-or-narrower scope and a new JTI. */
public record TaskTokenRotateCommand(
        String currentToken,
        long expectedGrantVersion,
        Set<String> allowedTools,
        Collection<TaskProviderGrantRequest> providerRequests,
        Duration lifetime) {

    public TaskTokenRotateCommand {
        currentToken = requireToken(currentToken);
        if (expectedGrantVersion < 0) {
            throw new IllegalArgumentException("expectedGrantVersion must not be negative");
        }
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
        providerRequests = java.util.List.copyOf(
                Objects.requireNonNull(providerRequests, "providerRequests"));
        lifetime = Objects.requireNonNull(lifetime, "lifetime");
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank() || value.length() > 16384) {
            throw new IllegalArgumentException("currentToken must be a bounded non-blank value");
        }
        return value;
    }

    @Override
    public String toString() {
        return "TaskTokenRotateCommand[token=[REDACTED]]";
    }
}
