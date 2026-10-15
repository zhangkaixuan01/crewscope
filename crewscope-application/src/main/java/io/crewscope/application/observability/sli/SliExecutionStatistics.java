package io.crewscope.application.observability.sli;

/**
 * Terminal task-execution and agent-run counts inside one window (M12-I03). Executions
 * exclude user cancellations and policy denials from the success denominator — those are
 * requester or governance outcomes, not reliability failures; the agent-run view has no
 * policy-denial classification and therefore does not exclude anything (ADR-037 note).
 */
public record SliExecutionStatistics(
        long executionsTerminal,
        long executionsCompleted,
        long executionsFailed,
        long executionsCancelled,
        long executionsPolicyDenied,
        long agentRunsTerminal,
        long agentRunsCompleted,
        long agentRunsFailed,
        long agentRunsCancelled,
        long agentRunsInterrupted) {

    /** The execution success denominator: terminal minus user cancels and policy denials. */
    public long executionDenominator() {
        return executionsTerminal - executionsCancelled - executionsPolicyDenied;
    }
}
