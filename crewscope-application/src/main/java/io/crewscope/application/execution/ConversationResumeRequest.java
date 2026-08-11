package io.crewscope.application.execution;

import io.crewscope.application.conversation.ClarificationAnswers;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.Message;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted answer that resumes one pending interrupt on the same logical invocation. */
public record ConversationResumeRequest(
        RuntimeInvocationId invocationId,
        AgentRuntimeSession runtimeSession,
        ExecutionInterruptToken interruptToken,
        UUID resumeRequestId,
        Message answerMessage,
        Optional<ClarificationAnswers> clarificationAnswers,
        UUID correlationId,
        PlatformExecutionContext platformContext) {

    public ConversationResumeRequest {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        runtimeSession = ConversationExecutionRequest.requireActiveSession(runtimeSession);
        interruptToken = Objects.requireNonNull(interruptToken, "interruptToken");
        resumeRequestId = Objects.requireNonNull(resumeRequestId, "resumeRequestId");
        answerMessage = ConversationExecutionRequest.requireUserMessage(
                runtimeSession, answerMessage);
        clarificationAnswers = Objects.requireNonNull(
                clarificationAnswers, "clarificationAnswers");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        platformContext = Objects.requireNonNull(platformContext, "platformContext");
        platformContext.requireMatches(runtimeSession, invocationId, correlationId);
    }

    /** Compatibility constructor for non-clarification interrupt tests and future approvals. */
    public ConversationResumeRequest(
            RuntimeInvocationId invocationId,
            AgentRuntimeSession runtimeSession,
            ExecutionInterruptToken interruptToken,
            UUID resumeRequestId,
            Message answerMessage,
            UUID correlationId,
            PlatformExecutionContext platformContext) {
        this(
                invocationId,
                runtimeSession,
                interruptToken,
                resumeRequestId,
                answerMessage,
                Optional.empty(),
                correlationId,
                platformContext);
    }
}
