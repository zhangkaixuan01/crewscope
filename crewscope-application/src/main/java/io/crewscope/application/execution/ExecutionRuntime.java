package io.crewscope.application.execution;

import java.util.concurrent.CompletionStage;

/** Framework-free Port for one logical Conversation execution and its resumable stream segments. */
public interface ExecutionRuntime {

    RuntimeDescriptor descriptor();

    RuntimeCapabilities capabilities();

    ExecutionHandle invokeConversation(ConversationExecutionRequest request);

    ExecutionHandle resumeConversation(ConversationResumeRequest request);

    CompletionStage<ExecutionCancelResult> cancel(ConversationCancelRequest request);
}
