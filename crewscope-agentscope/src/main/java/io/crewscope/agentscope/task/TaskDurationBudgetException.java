package io.crewscope.agentscope.task;

/** Internal marker used to classify a bounded AgentScope execution timeout. */
final class TaskDurationBudgetException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}
