package io.crewscope.application.execution;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.ClarificationAnswers;
import io.crewscope.application.conversation.ConversationConfigurationRefreshGuard;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.PostConversationMessageCommand;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.Message;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Coordinates committed USER Messages with trusted Personal Agent runtime segments. */
public final class PersonalAgentInvocationService implements ConversationConfigurationRefreshGuard {

    private static final String INVOCATION_NAMESPACE =
            "crewscope:personal-agent-invocation:v1:";
    private static final String SEGMENT_NAMESPACE =
            "crewscope:personal-agent-segment:v1:";
    private static final int DEFAULT_TERMINAL_RETENTION = 1_000;

    private final ConversationApplicationService conversationService;
    private final TaskIntentApplicationService taskIntentService;
    private final MessageRepository messageRepository;
    private final PersonalAgentExecutionContextResolver contextResolver;
    private final ExecutionRuntime runtime;
    private final ConversationExecutionEventMapper eventMapper;
    private final TimeProvider timeProvider;
    private final int terminalRetention;
    private final ConcurrentMap<RuntimeInvocationId, InvocationState> invocations =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<ConversationExecutionKey, Object> configurationBoundaries =
            new ConcurrentHashMap<>();
    private final Queue<RuntimeInvocationId> terminalOrder = new ArrayDeque<>();

    public PersonalAgentInvocationService(
            ConversationApplicationService conversationService,
            TaskIntentApplicationService taskIntentService,
            MessageRepository messageRepository,
            PersonalAgentExecutionContextResolver contextResolver,
            ExecutionRuntime runtime,
            ConversationExecutionEventMapper eventMapper,
            TimeProvider timeProvider) {
        this(
                conversationService,
                taskIntentService,
                messageRepository,
                contextResolver,
                runtime,
                eventMapper,
                timeProvider,
                DEFAULT_TERMINAL_RETENTION);
    }

    PersonalAgentInvocationService(
            ConversationApplicationService conversationService,
            TaskIntentApplicationService taskIntentService,
            MessageRepository messageRepository,
            PersonalAgentExecutionContextResolver contextResolver,
            ExecutionRuntime runtime,
            ConversationExecutionEventMapper eventMapper,
            TimeProvider timeProvider,
            int terminalRetention) {
        this.conversationService = Objects.requireNonNull(
                conversationService, "conversationService");
        this.taskIntentService = Objects.requireNonNull(taskIntentService, "taskIntentService");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        if (terminalRetention < 1) {
            throw new IllegalArgumentException("terminalRetention must be positive");
        }
        this.terminalRetention = terminalRetention;
    }

    /** Commits the owner's prompt and starts, or replays, its stable logical Invocation. */
    public ConversationAgentSegment invoke(
            TeamCommandContext context,
            TeamId teamId,
            ConversationId conversationId,
            String message) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        contextResolver.requireOwner(
                trusted.access(),
                organizationId,
                teamId,
                conversationId,
                trusted.correlationId());
        IdempotencyKey messageKey = scopedKey("invoke", trusted.idempotencyKey());
        CommandExecution<ConversationMessageAppend> posted = conversationService.postUserMessage(
                new TeamCommandContext(
                        trusted.access(),
                        messageKey,
                        trusted.correlationId(),
                        trusted.causationId()),
                teamId,
                conversationId,
                PostConversationMessageCommand.fromMarkdown(message));
        Message input = committedMessage(posted, organizationId, conversationId, messageKey);
        ConversationExecutionKey executionKey = new ConversationExecutionKey(
                organizationId, teamId, conversationId);
        synchronized (configurationBoundary(executionKey)) {
            RuntimeInvocationId invocationId = invocationId(input);
            InvocationState candidate = new InvocationState(
                    organizationId,
                    Objects.requireNonNull(teamId, "teamId"),
                    Objects.requireNonNull(conversationId, "conversationId"),
                    trusted.access().actor().id(),
                    input.id());
            InvocationState state = invocations.putIfAbsent(invocationId, candidate);
            if (state == null) {
                state = candidate;
            }
            synchronized (state) {
                state.requireRequest(organizationId, teamId, conversationId, trusted);
                if (state.initialSegment != null) {
                    return state.initialSegment.replayed();
                }
                ResolvedPersonalAgentExecution resolved = contextResolver.resolve(
                        trusted.access(),
                        organizationId,
                        teamId,
                        conversationId,
                        invocationId,
                        trusted.correlationId());
                UUID segmentId = segmentId(input.id().value());
                ExecutionEventMappingContext mappingContext = new ExecutionEventMappingContext(
                        resolved.platformContext(),
                        segmentId,
                        Optional.of(posted.receipt().domainEventId()));
                state.status = InvocationStatus.ACTIVE;
                ExecutionHandle handle = runtime.invokeConversation(new ConversationExecutionRequest(
                        invocationId,
                        resolved.runtimeSession(),
                        input,
                        Optional.empty(),
                        trusted.correlationId(),
                        resolved.platformContext()));
                ReplayableExecutionSegment publisher = publisher(
                        state, handle, mappingContext, organizationId);
                state.initialSegment = new StoredSegment(invocationId, segmentId, publisher);
                return state.initialSegment.first();
            }
        }
    }

    /** Commits an owner answer and resumes only the server-retained pending Interrupt token. */
    public ConversationAgentSegment resume(
            TeamCommandContext context,
            TeamId teamId,
            ConversationId conversationId,
            RuntimeInvocationId invocationId,
            ClarificationAnswers answers) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        ClarificationAnswers requiredAnswers = Objects.requireNonNull(answers, "answers");
        InvocationState state = requireInvocation(invocationId);
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        ConversationExecutionKey executionKey = new ConversationExecutionKey(
                organizationId, teamId, conversationId);
        synchronized (configurationBoundary(executionKey)) {
          synchronized (state) {
            state.requireRequest(organizationId, teamId, conversationId, trusted);
            String requestHash = digest(requiredAnswers.canonicalValue());
            ResumeAttempt replay = state.resumeSegments.get(trusted.idempotencyKey().value());
            if (replay != null) {
                if (!replay.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            trusted.idempotencyKey().value(),
                            replay.requestHash(),
                            requestHash);
                }
                return replay.segment().replayed();
            }
            if (state.status != InvocationStatus.INTERRUPTED || state.interruptToken.isEmpty()) {
                throw new PolicyDeniedException("resume this Personal Agent Invocation");
            }
            IdempotencyKey messageKey = scopedKey(
                    "resume:" + invocationId, trusted.idempotencyKey());
            CommandExecution<ConversationMessageAppend> posted =
                    conversationService.postUserMessage(
                            new TeamCommandContext(
                                    trusted.access(),
                                    messageKey,
                                    trusted.correlationId(),
                                    trusted.causationId()),
                            teamId,
                            conversationId,
                            PostConversationMessageCommand.fromMarkdown(
                                    requiredAnswers.toMarkdown()));
            Message input = committedMessage(posted, organizationId, conversationId, messageKey);
            ResolvedPersonalAgentExecution resolved = contextResolver.resolve(
                    trusted.access(),
                    organizationId,
                    teamId,
                    conversationId,
                    invocationId,
                    trusted.correlationId());
            UUID segmentId = segmentId(input.id().value());
            ExecutionInterruptToken token = state.interruptToken.orElseThrow();
            ExecutionHandle handle = runtime.resumeConversation(new ConversationResumeRequest(
                    invocationId,
                    resolved.runtimeSession(),
                    token,
                    input.id().value(),
                    input,
                    Optional.of(requiredAnswers),
                    trusted.correlationId(),
                    resolved.platformContext()));
            state.status = InvocationStatus.ACTIVE;
            state.interruptToken = Optional.empty();
            ReplayableExecutionSegment publisher = publisher(
                    state,
                    handle,
                    new ExecutionEventMappingContext(
                            resolved.platformContext(),
                            segmentId,
                            Optional.of(posted.receipt().domainEventId())),
                    organizationId);
            StoredSegment stored = new StoredSegment(invocationId, segmentId, publisher);
            state.resumeSegments.put(
                    trusted.idempotencyKey().value(), new ResumeAttempt(requestHash, stored));
            return stored.first();
          }
        }
    }

    /** Convenience overload retained for internal callers while HTTP uses field-keyed answers. */
    public ConversationAgentSegment resume(
            TeamCommandContext context,
            TeamId teamId,
            ConversationId conversationId,
            RuntimeInvocationId invocationId,
            String answer) {
        return resume(
                context,
                teamId,
                conversationId,
                invocationId,
                new ClarificationAnswers(Map.of("answer", answer)));
    }

    /** Propagates an explicit owner cancellation without treating SSE disconnect as cancellation. */
    public ConversationAgentCancelExecution cancel(
            TeamCommandContext context,
            TeamId teamId,
            ConversationId conversationId,
            RuntimeInvocationId invocationId,
            String reason) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        contextResolver.requireOwner(
                trusted.access(),
                organizationId,
                teamId,
                conversationId,
                trusted.correlationId());
        InvocationState state = invocations.get(invocationId);
        if (state == null) {
            return new ConversationAgentCancelExecution(
                    invocationId,
                    CompletableFuture.completedFuture(ExecutionCancelResult.NOT_FOUND),
                    false);
        }
        String normalizedReason = Objects.requireNonNull(reason, "reason").strip();
        synchronized (state) {
            state.requireRequest(organizationId, teamId, conversationId, trusted);
            CancelAttempt existing = state.cancelAttempts.get(trusted.idempotencyKey().value());
            String requestHash = digest(normalizedReason);
            if (existing != null) {
                if (!existing.requestHash.equals(requestHash)) {
                    throw new IdempotencyConflictException(
                            trusted.idempotencyKey().value(),
                            existing.requestHash,
                            requestHash);
                }
                return new ConversationAgentCancelExecution(
                        invocationId, existing.result, true);
            }
            ResolvedPersonalAgentExecution resolved = contextResolver.resolve(
                    trusted.access(),
                    organizationId,
                    teamId,
                    conversationId,
                    invocationId,
                    trusted.correlationId());
            CompletionStage<ExecutionCancelResult> result = runtime.cancel(
                    new ConversationCancelRequest(
                            invocationId,
                            resolved.runtimeSession(),
                            normalizedReason,
                            trusted.correlationId(),
                            resolved.platformContext()));
            state.cancelAttempts.put(
                    trusted.idempotencyKey().value(), new CancelAttempt(requestHash, result));
            return new ConversationAgentCancelExecution(invocationId, result, false);
        }
    }

    /** Blocks configuration replacement while a call runs or waits on a retained Interrupt. */
    @Override
    public void requireSafe(
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId) {
        ConversationExecutionKey executionKey = new ConversationExecutionKey(
                organizationId, teamId, conversationId);
        synchronized (configurationBoundary(executionKey)) {
            requireSafeInsideBoundary(executionKey);
        }
    }

    @Override
    public <T> T atSafePoint(
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId,
            Supplier<T> action) {
        ConversationExecutionKey executionKey = new ConversationExecutionKey(
                organizationId, teamId, conversationId);
        synchronized (configurationBoundary(executionKey)) {
            requireSafeInsideBoundary(executionKey);
            return Objects.requireNonNull(action, "action").get();
        }
    }

    private void requireSafeInsideBoundary(ConversationExecutionKey executionKey) {
        for (InvocationState state : invocations.values()) {
            synchronized (state) {
                if (state.organizationId.equals(executionKey.organizationId())
                        && state.teamId.equals(executionKey.teamId())
                        && state.conversationId.equals(executionKey.conversationId())
                        && state.status != InvocationStatus.TERMINAL) {
                    throw new PolicyDeniedException(
                            "refresh configuration while a Personal Agent Invocation is active or interrupted");
                }
            }
        }
    }

    private Object configurationBoundary(ConversationExecutionKey executionKey) {
        return configurationBoundaries.computeIfAbsent(executionKey, ignored -> new Object());
    }

    private ReplayableExecutionSegment publisher(
            InvocationState state,
            ExecutionHandle handle,
            ExecutionEventMappingContext mappingContext,
            OrganizationId organizationId) {
        return new ReplayableExecutionSegment(
                handle,
                mappingContext,
                eventMapper,
                candidate -> conversationService.commitAgentMessage(
                        candidate,
                        organizationId,
                        mappingContext.platformContext().correlationId(),
                        mappingContext.causationDomainEventId()),
                candidate -> taskIntentService.commitAgentProposal(
                        candidate,
                        mappingContext.platformContext(),
                        mappingContext.causationDomainEventId()),
                (status, token) -> terminal(state, mappingContext.platformContext().invocationId(), status, token),
                timeProvider);
    }

    private void terminal(
            InvocationState state,
            RuntimeInvocationId invocationId,
            ExecutionTerminalStatus status,
            Optional<ExecutionInterruptToken> token) {
        synchronized (state) {
            state.status = status == ExecutionTerminalStatus.INTERRUPTED
                    ? InvocationStatus.INTERRUPTED
                    : InvocationStatus.TERMINAL;
            state.interruptToken = token;
        }
        if (status != ExecutionTerminalStatus.INTERRUPTED) {
            retainTerminal(invocationId);
        }
    }

    private synchronized void retainTerminal(RuntimeInvocationId invocationId) {
        terminalOrder.add(invocationId);
        while (terminalOrder.size() > terminalRetention) {
            RuntimeInvocationId oldest = terminalOrder.remove();
            invocations.remove(oldest);
        }
    }

    private InvocationState requireInvocation(RuntimeInvocationId invocationId) {
        InvocationState state = invocations.get(Objects.requireNonNull(invocationId, "invocationId"));
        if (state == null) {
            throw new AggregateNotFoundException("PersonalAgentInvocation", invocationId);
        }
        return state;
    }

    private Message committedMessage(
            CommandExecution<ConversationMessageAppend> execution,
            OrganizationId organizationId,
            ConversationId conversationId,
            IdempotencyKey messageKey) {
        return execution.result()
                .map(ConversationMessageAppend::message)
                .or(() -> messageRepository.findByClientMessageKey(
                        organizationId, conversationId, messageKey.value()))
                .orElseThrow(() -> new IllegalStateException(
                        "Committed USER Message could not be reloaded"));
    }

    private static RuntimeInvocationId invocationId(Message message) {
        return new RuntimeInvocationId(stableUuid(INVOCATION_NAMESPACE, message.id().value()));
    }

    private static UUID segmentId(UUID messageId) {
        return stableUuid(SEGMENT_NAMESPACE, messageId);
    }

    private static UUID stableUuid(String namespace, UUID source) {
        return UUID.nameUUIDFromBytes(
                (namespace + source).getBytes(StandardCharsets.UTF_8));
    }

    private static IdempotencyKey scopedKey(String operation, IdempotencyKey clientKey) {
        return IdempotencyKey.from("pa-" + digest(operation + ":" + clientKey.value()));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private enum InvocationStatus {
        INITIALIZING,
        ACTIVE,
        INTERRUPTED,
        TERMINAL
    }

    private static final class InvocationState {

        private final OrganizationId organizationId;
        private final TeamId teamId;
        private final ConversationId conversationId;
        private final io.crewscope.domain.shared.id.PrincipalId ownerPrincipalId;
        private final io.crewscope.domain.conversation.MessageId inputMessageId;
        private final Map<String, ResumeAttempt> resumeSegments = new LinkedHashMap<>();
        private final Map<String, CancelAttempt> cancelAttempts = new LinkedHashMap<>();
        private InvocationStatus status = InvocationStatus.INITIALIZING;
        private Optional<ExecutionInterruptToken> interruptToken = Optional.empty();
        private StoredSegment initialSegment;

        private InvocationState(
                OrganizationId organizationId,
                TeamId teamId,
                ConversationId conversationId,
                io.crewscope.domain.shared.id.PrincipalId ownerPrincipalId,
                io.crewscope.domain.conversation.MessageId inputMessageId) {
            this.organizationId = organizationId;
            this.teamId = teamId;
            this.conversationId = conversationId;
            this.ownerPrincipalId = ownerPrincipalId;
            this.inputMessageId = inputMessageId;
        }

        private void requireRequest(
                OrganizationId organization,
                TeamId team,
                ConversationId conversation,
                TeamCommandContext context) {
            if (!organizationId.equals(organization)
                    || !teamId.equals(team)
                    || !conversationId.equals(conversation)
                    || !ownerPrincipalId.equals(context.access().actor().id())) {
                throw new AggregateNotFoundException("PersonalAgentInvocation", inputMessageId);
            }
        }
    }

    private record StoredSegment(
            RuntimeInvocationId invocationId,
            UUID segmentId,
            ReplayableExecutionSegment publisher) {
        private ConversationAgentSegment first() {
            return new ConversationAgentSegment(
                    invocationId,
                    segmentId,
                    publisher,
                    false);
        }

        private ConversationAgentSegment replayed() {
            return new ConversationAgentSegment(
                    invocationId,
                    segmentId,
                    publisher,
                    true);
        }
    }

    private record CancelAttempt(
            String requestHash, CompletionStage<ExecutionCancelResult> result) {}

    private record ResumeAttempt(String requestHash, StoredSegment segment) {}

    private record ConversationExecutionKey(
            OrganizationId organizationId,
            TeamId teamId,
            ConversationId conversationId) {
        private ConversationExecutionKey {
            organizationId = Objects.requireNonNull(organizationId, "organizationId");
            teamId = Objects.requireNonNull(teamId, "teamId");
            conversationId = Objects.requireNonNull(conversationId, "conversationId");
        }
    }
}
