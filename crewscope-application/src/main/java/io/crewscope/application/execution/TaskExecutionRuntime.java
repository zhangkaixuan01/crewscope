package io.crewscope.application.execution;

import java.util.concurrent.CompletionStage;

/** Framework-free Port for one durable Task AgentRun and its explicit business controls. */
public interface TaskExecutionRuntime extends ExecutionRuntimeProfile {

    TaskExecutionHandle executeTask(TaskExecutionRequest request);

    CompletionStage<TaskExecutionControlResult> controlTask(TaskExecutionControlRequest request);
}
