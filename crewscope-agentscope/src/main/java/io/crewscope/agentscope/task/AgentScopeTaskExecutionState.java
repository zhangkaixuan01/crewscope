package io.crewscope.agentscope.task;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.ExecutionInterruptKind;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.TaskAgentStateSafePoint;
import io.crewscope.application.execution.TaskApprovalInterruptTokens;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlRequest;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionRequest;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.AgentRunId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

final class AgentScopeTaskExecutionState {

    private final AgentScopeExecutionKey key;
    private final Clock clock;
    private final Set<UUID> appliedControls = new HashSet<>();
    private final AtomicLong nextSequence = new AtomicLong(1);
    private TaskExecutionRuntimeFacts facts;
    private HarnessAgent agent;
    private RuntimeContext context;
    private AgentRunId runId;
    private long segmentSequence;
    private int modelCalls;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private long cachedTokens;
    private boolean segmentRunning;
    private boolean segmentTerminal;
    private boolean logicalTerminal;
    private boolean resumeAuthorized;
    private UUID pauseControlRequestId;
    private String pauseReason;
    private String cancelReason;
    private String pendingReplyId;
    private List<ToolUseBlock> pendingTools = List.of();
    private io.crewscope.domain.task.TaskFactHash publishedContentHash;
    private final ConcurrentLinkedQueue<TaskExecutionEventPayload.ModelTransition>
            modelTransitions = new ConcurrentLinkedQueue<>();

    AgentScopeTaskExecutionState(
            AgentScopeExecutionKey key,
            TaskExecutionRuntimeFacts facts,
            HarnessAgent agent,
            RuntimeContext context,
            Clock clock) {
        this.key = key;
        this.clock = Objects.requireNonNull(clock, "clock");
        rebind(facts, agent, context);
    }

    synchronized void rebind(
            TaskExecutionRuntimeFacts replacement,
            HarnessAgent replacementAgent,
            RuntimeContext replacementContext) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(replacement, "facts");
        if (!key.equals(AgentScopeExecutionKey.from(required))) {
            throw new IllegalArgumentException("Task execution state owner changed");
        }
        if (facts != null && (!facts.lease().id().equals(required.lease().id())
                || !facts.lease().fencingToken().equals(required.lease().fencingToken()))) {
            throw new IllegalArgumentException("Task execution ownership epoch changed");
        }
        this.facts = required;
        this.agent = Objects.requireNonNull(replacementAgent, "agent");
        this.context = Objects.requireNonNull(replacementContext, "context");
    }

    synchronized void beginSegment(TaskExecutionRequest request) {
        if (logicalTerminal || segmentRunning) {
            throw new IllegalStateException("Task execution cannot start another active segment");
        }
        AgentRunSegmentKind kind = facts.agentRun().currentSegment().kind();
        if (kind == AgentRunSegmentKind.RESUME && !resumeAuthorized) {
            throw new IllegalStateException("Task RESUME segment has not been authorized");
        }
        runId = facts.agentRun().id();
        segmentSequence = facts.agentRun().currentSegment().sequence();
        nextSequence.set(1);
        segmentTerminal = false;
        segmentRunning = true;
        pauseReason = null;
        pauseControlRequestId = null;
        cancelReason = null;
    }

    synchronized TaskExecutionControlResult control(TaskExecutionControlRequest request) {
        if (!sameOwner(request.facts())) {
            return TaskExecutionControlResult.STALE_OWNER;
        }
        if (!appliedControls.add(request.controlRequestId())) {
            return TaskExecutionControlResult.ALREADY_APPLIED;
        }
        if (logicalTerminal) {
            return TaskExecutionControlResult.ALREADY_TERMINAL;
        }
        if (request.action() == TaskExecutionControlAction.RESUME) {
            resumeAuthorized = true;
            return TaskExecutionControlResult.ACCEPTED;
        }
        if (request.action() == TaskExecutionControlAction.PAUSE) {
            pauseControlRequestId = request.controlRequestId();
            pauseReason = request.reason();
        } else {
            cancelReason = request.reason();
            logicalTerminal = true;
        }
        if (segmentRunning) {
            interrupt();
        }
        return TaskExecutionControlResult.ACCEPTED;
    }

    synchronized boolean sameOwner(TaskExecutionRuntimeFacts candidate) {
        return key.equals(AgentScopeExecutionKey.from(candidate))
                && facts.lease().id().equals(candidate.lease().id())
                && facts.lease().fencingToken().equals(candidate.lease().fencingToken());
    }

    synchronized boolean advanceOwnershipForResume(
            TaskExecutionRuntimeFacts replacement,
            HarnessAgent replacementAgent,
            RuntimeContext replacementContext) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(replacement, "facts");
        if (sameOwner(required)) {
            this.facts = required;
            this.agent = Objects.requireNonNull(replacementAgent, "agent");
            this.context = Objects.requireNonNull(replacementContext, "context");
            return true;
        }
        boolean newerOwner = key.equals(AgentScopeExecutionKey.from(required))
                && segmentTerminal
                && !logicalTerminal
                && facts.execution().attempt() == required.execution().attempt()
                && facts.agentRun().id().equals(required.agentRun().id())
                && required.agentRun().currentSegment().kind() == AgentRunSegmentKind.RESUME
                && required.agentRun().currentSegment().sequence() > segmentSequence
                && !facts.lease().id().equals(required.lease().id())
                && required.lease().fencingToken().compareTo(facts.lease().fencingToken()) > 0;
        if (!newerOwner) {
            return false;
        }
        this.facts = required;
        this.agent = Objects.requireNonNull(replacementAgent, "agent");
        this.context = Objects.requireNonNull(replacementContext, "context");
        return true;
    }

    synchronized TaskExecutionEvent terminalEvent(AgentResultEvent event) {
        GenerateReason reason = Objects.requireNonNull(event.getResult(), "Agent result")
                .getGenerateReason();
        TaskExecutionEventPayload payload;
        if (cancelReason != null) {
            payload = new TaskExecutionEventPayload.Canceled(cancelReason);
            logicalTerminal = true;
        } else if (pauseReason != null) {
            payload = paused(pauseReason);
        } else if (reason == GenerateReason.INTERRUPTED) {
            payload = paused("Task Agent interrupted at a safe point");
        } else if (reason == GenerateReason.MAX_ITERATIONS) {
            payload = new TaskExecutionEventPayload.Failed(AgentScopeFailureClassifier.budget(
                    "MAX_ITERATIONS", "The Task Agent reached its iteration limit."));
            logicalTerminal = true;
        } else if (reason == GenerateReason.ALL_TOOLS_DENIED) {
            payload = new TaskExecutionEventPayload.Failed(new ExecutionFailure(
                    ExecutionFailureCategory.AUTHORIZATION,
                    false,
                    "The requested Task Tools were not authorized.",
                    Optional.of("ALL_TOOLS_DENIED")));
            logicalTerminal = true;
        } else if (reason == GenerateReason.PERMISSION_ASKING
                || reason == GenerateReason.TOOL_SUSPENDED
                || reason == GenerateReason.MIDDLEWARE_STOP_REQUESTED
                || reason == GenerateReason.REASONING_STOP_REQUESTED
                || reason == GenerateReason.ACTING_STOP_REQUESTED) {
            if (pendingTools.isEmpty()) {
                pending(null, event.getResult().getContentBlocks(ToolUseBlock.class).stream()
                        .filter(tool -> tool.getState() == ToolCallState.ASKING
                                || tool.getState() == ToolCallState.PENDING)
                        .toList());
            }
            payload = approvalPayload();
        } else {
            payload = new TaskExecutionEventPayload.Completed(Optional.empty());
            logicalTerminal = true;
        }
        return terminal(payload);
    }

    synchronized TaskExecutionEvent approvalEvent() {
        return terminal(approvalPayload());
    }

    private TaskExecutionEventPayload approvalPayload() {
        return new TaskExecutionEventPayload.ApprovalRequired(
                TaskApprovalInterruptTokens.from(
                        facts.execution().id(),
                        facts.agentRun().id(),
                        facts.agentRun().currentSegment().sequence()),
                ExecutionInterruptKind.TOOL_APPROVAL,
                "Approve the validated controlled Task plan to continue.");
    }

    synchronized TaskExecutionEvent failureEvent(ExecutionFailure failure) {
        logicalTerminal = true;
        return terminal(new TaskExecutionEventPayload.Failed(failure));
    }

    synchronized TaskExecutionEvent sourceFailed(ExecutionFailure failure) {
        if (cancelReason != null) {
            logicalTerminal = true;
            return terminal(new TaskExecutionEventPayload.Canceled(cancelReason));
        }
        if (pauseReason != null) {
            return terminal(paused(pauseReason));
        }
        return failureEvent(failure);
    }

    synchronized TaskExecutionEvent sourceCompletedWithoutResult() {
        return sourceFailed(new ExecutionFailure(
                ExecutionFailureCategory.INTERNAL,
                false,
                "The Task Agent stream ended without a terminal result.",
                Optional.of("TASK_RUNTIME_TERMINAL_MISSING")));
    }

    TaskExecutionEventPayload.Paused paused(String reason) {
        UUID controlRequestId = Objects.requireNonNull(
                pauseControlRequestId, "pauseControlRequestId");
        return new TaskExecutionEventPayload.Paused(
                new ExecutionInterruptToken(controlRequestId.toString()), reason);
    }

    TaskExecutionEvent terminal(TaskExecutionEventPayload payload) {
        if (segmentTerminal) {
            throw new IllegalStateException("Task segment already has a terminal event");
        }
        segmentTerminal = true;
        return event(payload);
    }

    synchronized TaskExecutionEvent usageEvent(ChatUsage usage) {
        if (usage.getInputTokens() < 0
                || usage.getOutputTokens() < 0
                || usage.getCachedTokens() < 0
                || usage.getCachedTokens() > usage.getInputTokens()) {
            throw new IllegalArgumentException("AgentScope reported invalid token usage");
        }
        inputTokens = Math.addExact(inputTokens, usage.getInputTokens());
        outputTokens = Math.addExact(outputTokens, usage.getOutputTokens());
        cachedTokens = Math.addExact(cachedTokens, usage.getCachedTokens());
        return event(new TaskExecutionEventPayload.UsageReported(
                inputTokens, outputTokens, cachedTokens, Math.addExact(inputTokens, outputTokens)));
    }

    void observeModelTransition(TaskExecutionEventPayload.ModelTransition transition) {
        modelTransitions.add(Objects.requireNonNull(transition, "transition"));
    }

    synchronized List<TaskExecutionEvent> drainModelTransitions() {
        List<TaskExecutionEvent> events = new ArrayList<>();
        TaskExecutionEventPayload.ModelTransition transition;
        while ((transition = modelTransitions.poll()) != null) {
            events.add(event(transition));
        }
        return events;
    }

    synchronized TaskExecutionEvent event(TaskExecutionEventPayload payload) {
        return new TaskExecutionEvent(
                facts.execution().id(),
                facts.execution().attempt(),
                runId != null ? runId : facts.agentRun().id(),
                segmentSequence > 0
                        ? segmentSequence
                        : facts.agentRun().currentSegment().sequence(),
                nextSequence.getAndIncrement(),
                UtcTimestamp.from(clock.instant()),
                payload);
    }

    synchronized int incrementModelCalls() {
        return ++modelCalls;
    }

    synchronized int incrementToolCalls() {
        return ++toolCalls;
    }

    synchronized long totalTokens() {
        return inputTokens + outputTokens;
    }

    synchronized void pending(String replyId, List<ToolUseBlock> tools) {
        pendingReplyId = replyId;
        pendingTools = List.copyOf(tools);
    }

    synchronized List<ToolUseBlock> pendingTools() {
        if (!pendingTools.isEmpty()) {
            return pendingTools;
        }
        AgentState state = agent.getDelegate().getAgentState(context);
        for (int index = state.getContext().size() - 1; index >= 0; index--) {
            List<ToolUseBlock> found = state.getContext().get(index)
                    .getContentBlocks(ToolUseBlock.class)
                    .stream()
                    .filter(tool -> tool.getState() == ToolCallState.ASKING
                            || tool.getState() == ToolCallState.PENDING)
                    .toList();
            if (!found.isEmpty()) {
                pendingTools = found;
                return found;
            }
        }
        return List.of();
    }

    synchronized boolean consumeResumeAuthorization() {
        if (!resumeAuthorized) {
            return false;
        }
        resumeAuthorized = false;
        return true;
    }

    synchronized void publishedContentHash(
            io.crewscope.domain.task.TaskFactHash contentHash) {
        publishedContentHash = Objects.requireNonNull(contentHash, "contentHash");
    }

    synchronized io.crewscope.domain.task.TaskFactHash publishedContentHash() {
        return Objects.requireNonNull(publishedContentHash, "publishedContentHash");
    }

    synchronized void detachSegment() {
        segmentRunning = false;
    }

    synchronized boolean segmentTerminal() {
        return segmentTerminal;
    }

    synchronized TaskExecutionRuntimeFacts facts() {
        return facts;
    }

    AgentScopeExecutionKey key() {
        return key;
    }

    synchronized HarnessAgent agent() {
        return agent;
    }

    synchronized RuntimeContext context() {
        return context;
    }

    void interrupt() {
        agent.getDelegate().interrupt(context);
    }

    synchronized void shutdown() {
        cancelReason = "Task runtime shutdown";
        logicalTerminal = true;
        if (segmentRunning) {
            interrupt();
        }
    }

    synchronized void requireSafeCheckpoint(TaskAgentStateSafePoint safePoint) {
        Objects.requireNonNull(safePoint, "safePoint");
        if (segmentRunning && !segmentTerminal) {
            throw new IllegalStateException(
                    "Task AgentState may be checkpointed only after a finite safe boundary");
        }
    }

    synchronized void requireRecoverySafe() {
        if (segmentRunning) {
            throw new IllegalStateException(
                    "Task AgentState cannot be replaced while a Segment is running");
        }
    }
}
