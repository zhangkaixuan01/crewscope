package io.crewscope.application.execution;

/** Agent runtime operations used by a Worker after durable events reach a safe checkpoint. */
public interface TaskAgentStateRuntime {

    TaskAgentStateCheckpointResult checkpointState(
            TaskExecutionRuntimeFacts facts,
            long segmentSequence,
            long eventSequence,
            TaskAgentStateSafePoint safePoint);

    TaskAgentStateRecoveryResult recoverState(TaskExecutionRuntimeFacts facts, int candidateLimit);
}
