package io.crewscope.application.execution;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Idempotent explicit-cancel result, including whether the process-local result was replayed. */
public record ConversationAgentCancelExecution(
        RuntimeInvocationId invocationId,
        CompletionStage<ExecutionCancelResult> result,
        boolean replayed) {

    public ConversationAgentCancelExecution {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        result = Objects.requireNonNull(result, "result");
    }
}
