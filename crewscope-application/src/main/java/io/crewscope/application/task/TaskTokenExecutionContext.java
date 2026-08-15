package io.crewscope.application.task;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskTokenGrantScope;
import java.util.Objects;

/** Server-verified Worker identity and authorization scope injected into a trusted request. */
public record TaskTokenExecutionContext(
        TaskCredentialGrantId grantId,
        long grantVersion,
        TaskTokenGrantScope scope,
        UtcTimestamp expiresAt) {
    public TaskTokenExecutionContext {
        grantId = Objects.requireNonNull(grantId, "grantId");
        if (grantVersion < 0) {
            throw new IllegalArgumentException("grantVersion must not be negative");
        }
        scope = Objects.requireNonNull(scope, "scope");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "TaskTokenExecutionContext[grantId=" + grantId
                + ", taskExecutionId=" + scope.taskExecutionId()
                + ", authorization=[REDACTED]]";
    }
}
