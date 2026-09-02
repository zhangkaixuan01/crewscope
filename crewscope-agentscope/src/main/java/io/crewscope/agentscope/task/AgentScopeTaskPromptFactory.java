package io.crewscope.agentscope.task;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.PlanStep;
import java.util.Objects;
import java.util.Set;

/** Builds the bounded prompt sent to an AgentScope Task Agent. */
final class AgentScopeTaskPromptFactory {

    private AgentScopeTaskPromptFactory() {}

    static String prompt(TaskExecutionRuntimeFacts facts, AgentRunSegmentKind kind) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        AgentRunSegmentKind requiredKind = Objects.requireNonNull(kind, "kind");
        if (required.stepExecution().isPresent()) {
            String key = required.stepExecution().orElseThrow().planStepKey();
            PlanStep step = required.planVersion().orElseThrow().steps().stream()
                    .filter(candidate -> candidate.key().equals(key))
                    .findFirst()
                    .orElseThrow();
            return "Execute only controlled Step '" + step.key() + "' (" + step.title()
                    + "). Use only its declared fixture Tool, update Todo cognition, and report the result."
                    + " Todo never changes CrewScope domain Step state.";
        }
        if (required.planVersion().isEmpty()) {
            boolean codingTask = required.policySnapshot().capabilities().containsAll(
                    Set.of(io.crewscope.domain.task.ExecutionCapability.WORKTREE,
                            io.crewscope.domain.task.ExecutionCapability.SANDBOX));
            String exactPlan = codingTask
                    ? """
                      # Controlled Task Plan
                      - `implement` | IMPLEMENTATION | Implement the requested code change | deps=- | capabilities=WORKTREE,SANDBOX | tools=fixture_execute | critical=true
                      - `validate` | VALIDATION | Run the required validation | deps=implement | capabilities=PLAN | tools=fixture_validate | critical=true
                      """
                    : """
                      # Controlled Task Plan
                      - `inspect` | ANALYSIS | Inspect the task input | deps=- | capabilities=PLAN | tools=fixture_inspect | critical=true
                      - `execute` | IMPLEMENTATION | Execute the requested work | deps=inspect | capabilities=PLAN | tools=fixture_execute | critical=true
                      - `validate` | VALIDATION | Validate the result | deps=execute | capabilities=STRUCTURED_OUTPUT | tools=fixture_validate | critical=true
                      """;
            return TaskPromptBoundary.taskBrief(required)
                    + "\n\nPublish the exact controlled plan below without changing it. Make exactly "
                    + "these calls in order: plan_enter once, todo_write once, "
                    + "validate_task_plan once, plan_write once with the same complete Markdown, "
                    + "then plan_exit once. Do not call fixture tools while planning and do not "
                    + "repeat validation after it returns VALID.\n\n" + exactPlan.strip();
        }
        return "Continue the published controlled Task plan in " + requiredKind
                + " mode. Use only declared fixture Tools and maintain Todo cognition."
                + " Todo does not change CrewScope domain facts.";
    }
}
