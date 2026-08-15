package io.crewscope.application.execution;

/** Durable secondary-storage Port for Task AgentScope state checkpoints and recovery. */
public interface TaskAgentStateSnapshotService {

    TaskAgentStateCheckpointResult checkpoint(TaskAgentStateCheckpointCommand command);

    TaskAgentStateRecoveryResult recover(TaskAgentStateRecoveryCommand command);
}
