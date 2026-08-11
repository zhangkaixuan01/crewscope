package io.crewscope.application.execution;

import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Opens stateful, single-segment mappers for the framework-neutral ExecutionRuntime stream. */
public final class ConversationExecutionEventMapper {

    private static final String TASK_INTENT_SCHEMA_ID = "task-intent/v1";

    private final Validator validator;

    public ConversationExecutionEventMapper(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Session open(ExecutionEventMappingContext context) {
        return new Session(Objects.requireNonNull(context, "context"), validator);
    }

    /**
     * Stateful protocol boundary for one invoke or resume segment.
     *
     * <p>Exact replays are absorbed without repeating realtime or persistence candidates. Gaps,
     * conflicting replays and events after a terminal fail closed.
     */
    public static final class Session {

        private static final int MAX_EVENTS = 100_000;

        private final ExecutionEventMappingContext context;
        private final Validator validator;
        private final Map<Long, ExecutionEvent> acceptedEvents = new HashMap<>();
        private final StringBuilder publicText = new StringBuilder();
        private long nextSequence = 1;
        private ExecutionSegmentKind segmentKind;
        private ExecutionTerminalStatus terminalStatus;
        private TaskIntentV1 pendingTaskIntent;

        private Session(ExecutionEventMappingContext context, Validator validator) {
            this.context = context;
            this.validator = validator;
        }

        public ExecutionEventMappingResult accept(ExecutionEvent event) {
            ExecutionEvent required = Objects.requireNonNull(event, "event");
            requireInvocation(required);
            if (required.sequence() < nextSequence) {
                ExecutionEvent previous = acceptedEvents.get(required.sequence());
                if (required.equals(previous)) {
                    return ExecutionEventMappingResult.duplicateEvent();
                }
                throw new ExecutionProtocolException(
                        "event sequence conflicts with an already accepted event");
            }
            if (required.sequence() > nextSequence) {
                throw new ExecutionProtocolException(
                        "event sequence must be contiguous from one; expected " + nextSequence);
            }
            if (terminalStatus != null) {
                throw new ExecutionProtocolException("event received after the terminal event");
            }
            if (acceptedEvents.size() >= MAX_EVENTS) {
                throw new ExecutionProtocolException("event segment exceeds the supported limit");
            }
            validateShape(required);
            ExecutionEventMappingResult result = map(required);
            acceptedEvents.put(required.sequence(), required);
            nextSequence++;
            return result;
        }

        /** Proves the upstream completed only after one mapped terminal event. */
        public ExecutionTerminalStatus complete() {
            if (terminalStatus == null) {
                throw new ExecutionProtocolException(
                        "the event stream completed without a terminal event");
            }
            return terminalStatus;
        }

        private void requireInvocation(ExecutionEvent event) {
            RuntimeInvocationId expected = context.platformContext().invocationId();
            if (!expected.equals(event.invocationId())) {
                throw new ExecutionProtocolException("event belongs to another invocation");
            }
        }

        private void validateShape(ExecutionEvent event) {
            if (nextSequence == 1 && !(event.payload() instanceof ExecutionEventPayload.Started)) {
                throw new ExecutionProtocolException("the first event must be STARTED");
            }
            if (nextSequence > 1 && event.payload() instanceof ExecutionEventPayload.Started) {
                throw new ExecutionProtocolException("STARTED can appear only once");
            }
        }

        private ExecutionEventMappingResult map(ExecutionEvent event) {
            Optional<AgentMessageCandidate> messageCandidate = Optional.empty();
            Optional<TaskIntentOutputCandidate> taskIntentCandidate = Optional.empty();
            Optional<RealtimeEventEnvelope<? extends AguiTransientPayload>> transientEvent;

            if (event.payload() instanceof ExecutionEventPayload.Started started) {
                segmentKind = started.segmentKind();
                transientEvent = Optional.of(transientEvent(
                        event,
                        EventType.from("RUN_STARTED"),
                        new AguiTransientPayload.RunStarted(
                                threadId(), runId(), context.segmentId(), segmentKind)));
            } else if (event.payload() instanceof ExecutionEventPayload.TextDelta textDelta) {
                appendPublicText(textDelta.text());
                transientEvent = Optional.of(transientEvent(
                        event,
                        EventType.from("TEXT_MESSAGE_CONTENT"),
                        new AguiTransientPayload.TextMessageContent(
                                threadId(),
                                runId(),
                                context.segmentId(),
                                messageId(),
                                textDelta.text())));
            } else if (event.payload() instanceof ExecutionEventPayload.StructuredOutput<?> output) {
                captureStructuredOutput(output);
                // Structured model values remain server-side until validated and committed.
                transientEvent = Optional.empty();
            } else if (event.payload() instanceof ExecutionEventPayload.Interrupted interrupted) {
                terminalStatus = ExecutionTerminalStatus.INTERRUPTED;
                transientEvent = Optional.of(transientEvent(
                        event,
                        EventType.from("RUN_INTERRUPTED"),
                        new AguiTransientPayload.RunInterrupted(
                                threadId(),
                                runId(),
                                context.segmentId(),
                                interrupted.token().value(),
                                interrupted.kind(),
                                interrupted.safePrompt(),
                                interrupted.clarification())));
            } else if (event.payload() instanceof ExecutionEventPayload.Canceled) {
                terminalStatus = ExecutionTerminalStatus.CANCELED;
                transientEvent = Optional.of(transientEvent(
                        event,
                        EventType.from("RUN_FINISHED"),
                        new AguiTransientPayload.RunFinished(
                                threadId(),
                                runId(),
                                context.segmentId(),
                                ExecutionTerminalStatus.CANCELED)));
            } else if (event.payload() instanceof ExecutionEventPayload.Failed failed) {
                terminalStatus = ExecutionTerminalStatus.FAILED;
                ExecutionFailure failure = failed.failure();
                transientEvent = Optional.of(transientEvent(
                        event,
                        EventType.from("RUN_ERROR"),
                        new AguiTransientPayload.RunError(
                                threadId(),
                                runId(),
                                context.segmentId(),
                                failure.safeMessage(),
                                failure.runtimeCode(),
                                failure.retryable())));
            } else if (event.payload() instanceof ExecutionEventPayload.Completed) {
                terminalStatus = ExecutionTerminalStatus.COMPLETED;
                transientEvent = Optional.of(transientEvent(
                        event,
                        EventType.from("RUN_FINISHED"),
                        new AguiTransientPayload.RunFinished(
                                threadId(),
                                runId(),
                                context.segmentId(),
                                ExecutionTerminalStatus.COMPLETED)));
                messageCandidate = completedMessage(event);
                taskIntentCandidate = completedTaskIntent(event);
            } else {
                // The sealed family makes this unreachable in this release and keeps future
                // additions fail-closed until their persistence and disclosure policy is explicit.
                throw new ExecutionProtocolException("unsupported execution event payload");
            }

            return new ExecutionEventMappingResult(
                    transientEvent, messageCandidate, taskIntentCandidate, false);
        }

        private void appendPublicText(String delta) {
            if (publicText.length() + delta.length() > MessageContent.MAX_LENGTH) {
                throw new ExecutionProtocolException(
                        "assistant message exceeds the supported persisted length");
            }
            publicText.append(delta);
        }

        private void captureStructuredOutput(ExecutionEventPayload.StructuredOutput<?> output) {
            if (!TASK_INTENT_SCHEMA_ID.equals(output.spec().schemaId())) {
                // Unknown structured schemas are deliberately not serialized into AG-UI.
                return;
            }
            if (output.spec().javaType() != TaskIntentV1.class
                    || !(output.value() instanceof TaskIntentV1 taskIntent)) {
                throw new ExecutionProtocolException(
                        "task-intent/v1 must carry TaskIntentV1");
            }
            if (pendingTaskIntent != null) {
                throw new ExecutionProtocolException(
                        "task-intent/v1 can appear only once per execution segment");
            }
            Set<ConstraintViolation<TaskIntentV1>> violations = validator.validate(taskIntent);
            if (!violations.isEmpty()) {
                // Violation messages can reflect model data; expose only a stable safe error.
                throw new ExecutionProtocolException(
                        "task-intent/v1 failed application validation");
            }
            pendingTaskIntent = taskIntent;
        }

        private Optional<AgentMessageCandidate> completedMessage(ExecutionEvent event) {
            if (publicText.toString().isBlank()) {
                return Optional.empty();
            }
            PlatformExecutionContext platform = context.platformContext();
            return Optional.of(new AgentMessageCandidate(
                    platform.invocationId(),
                    context.segmentId(),
                    platform.conversationId(),
                    platform.agentParticipantId(),
                    platform.personalAgentPrincipalId(),
                    new MessageContent(publicText.toString()),
                    event.occurredAt()));
        }

        private Optional<TaskIntentOutputCandidate> completedTaskIntent(ExecutionEvent event) {
            if (pendingTaskIntent == null) {
                return Optional.empty();
            }
            PlatformExecutionContext platform = context.platformContext();
            return Optional.of(new TaskIntentOutputCandidate(
                    platform.invocationId(),
                    context.segmentId(),
                    platform.conversationId(),
                    pendingTaskIntent,
                    event.occurredAt()));
        }

        private <T extends AguiTransientPayload> RealtimeEventEnvelope<T> transientEvent(
                ExecutionEvent event, EventType eventType, T payload) {
            PlatformExecutionContext platform = context.platformContext();
            return RealtimeEventEnvelope.transientAgUi(
                    deterministicEventId(event.sequence()),
                    eventType,
                    SchemaVersion.V1,
                    platform.correlationId(),
                    context.causationDomainEventId(),
                    event.occurredAt(),
                    payload);
        }

        private UUID deterministicEventId(long sequence) {
            String seed = "crewscope:agui:v1:" + context.segmentId() + ':' + sequence;
            return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        }

        private String threadId() {
            return context.platformContext().conversationId().toString();
        }

        private String runId() {
            return context.platformContext().invocationId().toString();
        }

        private String messageId() {
            return context.segmentId().toString();
        }
    }
}
