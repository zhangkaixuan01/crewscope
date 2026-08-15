package io.crewscope.application.task;

import io.crewscope.domain.task.TaskCredentialGrant;
import java.util.Objects;

/** One-time plaintext token result returned only to the trusted Worker preparation boundary. */
public record TaskTokenIssueResult(
        String token, TaskCredentialGrant grant, TaskTokenExecutionContext context) {
    public TaskTokenIssueResult {
        token = Objects.requireNonNull(token, "token");
        grant = Objects.requireNonNull(grant, "grant");
        context = Objects.requireNonNull(context, "context");
    }

    @Override
    public String toString() {
        return "TaskTokenIssueResult[grantId=" + grant.id() + ", token=[REDACTED]]";
    }
}
