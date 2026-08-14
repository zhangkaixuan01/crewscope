package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Immutable terminal outcome; large results are represented only by RuntimeArtifact metadata. */
public record AgentRunTerminal(
        AgentRunStatus status,
        Optional<String> failureCode,
        Optional<RuntimeArtifactId> resultArtifactId,
        UtcTimestamp occurredAt) {

    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    public AgentRunTerminal {
        status = Objects.requireNonNull(status, "status");
        failureCode = Objects.requireNonNull(failureCode, "failureCode")
                .map(String::strip);
        resultArtifactId = Objects.requireNonNull(resultArtifactId, "resultArtifactId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (!status.isTerminal()) {
            throw new DomainValidationException("agentRun.terminal.status", "must be terminal");
        }
        if ((status == AgentRunStatus.FAILED) != failureCode.isPresent()) {
            throw new DomainValidationException(
                    "agentRun.terminal.failureCode", "must exist exactly for FAILED");
        }
        failureCode.ifPresent(value -> {
            if (!FAILURE_CODE.matcher(value).matches()) {
                throw new DomainValidationException(
                        "agentRun.terminal.failureCode", "must be a stable uppercase reason code");
            }
        });
        if (status == AgentRunStatus.CANCELLED && resultArtifactId.isPresent()) {
            throw new DomainValidationException(
                    "agentRun.terminal.resultArtifactId", "must be absent for CANCELLED");
        }
    }
}
