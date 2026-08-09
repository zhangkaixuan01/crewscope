package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.application.event.json.RealtimeEventEnvelopeJsonCodec;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.RealtimeEventEnvelope;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.WorkspaceType;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Fixture contract evidence for the M2-I06 event mapping and disclosure boundary. */
class ConversationExecutionEventMapperM2I06Test {

    private static final UtcTimestamp T0 =
            UtcTimestamp.from(Instant.parse("2026-08-09T08:00:00Z"));
    private static final UtcTimestamp T1 =
            UtcTimestamp.from(Instant.parse("2026-08-09T08:00:01Z"));
    private static final UtcTimestamp T2 =
            UtcTimestamp.from(Instant.parse("2026-08-09T08:00:02Z"));

    private final ConversationExecutionEventMapper mapper = new ConversationExecutionEventMapper(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void mapsPublicTextToTransientAguiAndOneCommitCandidate() {
        Fixture fixture = Fixture.create();
        UUID causationId = UUID.randomUUID();
        ConversationExecutionEventMapper.Session session = mapper.open(
                fixture.mappingContext(Optional.of(causationId)));

        ExecutionEventMappingResult started = session.accept(fixture.event(
                1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)));
        ExecutionEventMappingResult text = session.accept(fixture.event(
                2, T1, new ExecutionEventPayload.TextDelta("Public answer")));
        ExecutionEventMappingResult completed = session.accept(fixture.event(
                3, T2, new ExecutionEventPayload.Completed()));

        RealtimeEventEnvelope<?> startEnvelope = started.transientEvent().orElseThrow();
        assertEquals(StreamType.AG_UI, startEnvelope.streamType());
        assertEquals("RUN_STARTED", startEnvelope.eventType().value());
        assertEquals(Optional.empty(), startEnvelope.domainEventId());
        assertEquals(Optional.empty(), startEnvelope.aggregate());
        assertEquals(Optional.of(causationId), startEnvelope.causationId());
        assertEquals(fixture.context().correlationId(), startEnvelope.correlationId());
        assertEquals("TEXT_MESSAGE_CONTENT", text.transientEvent()
                .orElseThrow()
                .eventType()
                .value());
        assertEquals("RUN_FINISHED", completed.transientEvent()
                .orElseThrow()
                .eventType()
                .value());
        AgentMessageCandidate candidate = completed.messageCandidate().orElseThrow();
        assertEquals("Public answer", candidate.content().markdown());
        assertEquals(fixture.context().agentParticipantId(), candidate.participantId());
        assertEquals(fixture.context().personalAgentPrincipalId(), candidate.authorPrincipalId());
        assertEquals(ExecutionTerminalStatus.COMPLETED, session.complete());
    }

    @Test
    void releasesBeanValidatedTaskIntentOnlyAfterCompleted() {
        Fixture fixture = Fixture.create();
        ConversationExecutionEventMapper.Session session = mapper.open(
                fixture.mappingContext(Optional.empty()));
        TaskIntentV1 taskIntent = fixture.validTaskIntent();

        session.accept(fixture.event(
                1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)));
        ExecutionEventMappingResult structured = session.accept(fixture.event(
                2,
                T1,
                new ExecutionEventPayload.StructuredOutput<>(
                        new StructuredOutputSpec<>("task-intent/v1", TaskIntentV1.class),
                        taskIntent)));
        ExecutionEventMappingResult completed = session.accept(fixture.event(
                3, T2, new ExecutionEventPayload.Completed()));

        assertTrue(structured.transientEvent().isEmpty());
        assertTrue(structured.taskIntentCandidate().isEmpty());
        assertEquals(taskIntent, completed.taskIntentCandidate().orElseThrow().output());
        assertTrue(completed.messageCandidate().isEmpty());
    }

    @Test
    void rejectsInvalidTaskIntentWithoutReflectingModelValues() {
        Fixture fixture = Fixture.create();
        ConversationExecutionEventMapper.Session session = mapper.open(
                fixture.mappingContext(Optional.empty()));
        session.accept(fixture.event(
                1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)));
        TaskIntentV1 invalid = new TaskIntentV1(
                "1",
                "private-invalid-objective",
                List.of(),
                UUID.randomUUID().toString(),
                fixture.context().teamMemberId().toString(),
                null,
                null);

        ExecutionProtocolException exception = assertThrows(
                ExecutionProtocolException.class,
                () -> session.accept(fixture.event(
                        2,
                        T1,
                        new ExecutionEventPayload.StructuredOutput<>(
                                new StructuredOutputSpec<>(
                                        "task-intent/v1", TaskIntentV1.class),
                                invalid))));

        assertEquals("task-intent/v1 failed application validation", exception.getMessage());
        assertFalse(exception.getMessage().contains("private-invalid-objective"));
    }

    @Test
    void absorbsExactDuplicateWithoutRepeatingRealtimeOrPersistenceEffects() {
        Fixture fixture = Fixture.create();
        ConversationExecutionEventMapper.Session session = mapper.open(
                fixture.mappingContext(Optional.empty()));
        ExecutionEvent started = fixture.event(
                1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE));

        ExecutionEventMappingResult first = session.accept(started);
        ExecutionEventMappingResult replay = session.accept(started);

        assertFalse(first.duplicate());
        assertTrue(replay.duplicate());
        assertTrue(replay.transientEvent().isEmpty());
        assertTrue(replay.messageCandidate().isEmpty());
    }

    @Test
    void rejectsGapsAndConflictingReplays() {
        Fixture fixture = Fixture.create();
        ConversationExecutionEventMapper.Session gapSession = mapper.open(
                fixture.mappingContext(Optional.empty()));
        assertThrows(
                ExecutionProtocolException.class,
                () -> gapSession.accept(fixture.event(
                        2, T1, new ExecutionEventPayload.TextDelta("gap"))));

        ConversationExecutionEventMapper.Session conflictSession = mapper.open(
                fixture.mappingContext(Optional.empty()));
        conflictSession.accept(fixture.event(
                1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)));
        conflictSession.accept(fixture.event(
                2, T1, new ExecutionEventPayload.TextDelta("first")));
        assertThrows(
                ExecutionProtocolException.class,
                () -> conflictSession.accept(fixture.event(
                        2, T1, new ExecutionEventPayload.TextDelta("conflict"))));
    }

    @Test
    void hidesUnknownStructuredSchemaAndRequiresOneTerminal() {
        Fixture fixture = Fixture.create();
        ConversationExecutionEventMapper.Session session = mapper.open(
                fixture.mappingContext(Optional.empty()));
        session.accept(fixture.event(
                1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)));
        ExecutionEventMappingResult unknown = session.accept(fixture.event(
                2,
                T1,
                new ExecutionEventPayload.StructuredOutput<>(
                        new StructuredOutputSpec<>("future-output/v9", FutureOutput.class),
                        new FutureOutput("must-not-be-serialized"))));

        assertTrue(unknown.transientEvent().isEmpty());
        assertTrue(unknown.taskIntentCandidate().isEmpty());
        assertThrows(ExecutionProtocolException.class, session::complete);
        session.accept(fixture.event(3, T2, new ExecutionEventPayload.Completed()));
        assertEquals(ExecutionTerminalStatus.COMPLETED, session.complete());
        assertThrows(
                ExecutionProtocolException.class,
                () -> session.accept(fixture.event(
                        4, T2, new ExecutionEventPayload.TextDelta("late"))));
    }

    @Test
    void mapsInterruptCancelAndFailureToSafeTerminalPayloads() {
        Fixture fixture = Fixture.create();
        ExecutionEventPayload[] terminals = {
            new ExecutionEventPayload.Interrupted(
                    new ExecutionInterruptToken("resume-coordinate"),
                    ExecutionInterruptKind.CLARIFICATION,
                    "Please clarify the target"),
            new ExecutionEventPayload.Canceled("private cancellation reason"),
            new ExecutionEventPayload.Failed(new ExecutionFailure(
                    ExecutionFailureCategory.MODEL_RATE_LIMITED,
                    true,
                    "Model is temporarily unavailable",
                    Optional.of("MODEL_RATE_LIMITED")))
        };

        for (ExecutionEventPayload terminal : terminals) {
            ConversationExecutionEventMapper.Session session = mapper.open(
                    fixture.mappingContext(Optional.empty()));
            session.accept(fixture.event(
                    1, T0, new ExecutionEventPayload.Started(ExecutionSegmentKind.INVOKE)));
            RealtimeEventEnvelope<?> envelope = session.accept(fixture.event(2, T1, terminal))
                    .transientEvent()
                    .orElseThrow();
            assertTrue(Set.of("RUN_INTERRUPTED", "RUN_FINISHED", "RUN_ERROR")
                    .contains(envelope.eventType().value()));
            assertFalse(envelope.toString().contains("private cancellation reason"));
            assertTrue(session.complete() != ExecutionTerminalStatus.COMPLETED);
        }
    }

    @Test
    void projectsCommittedDomainEventWithStableAssociation() {
        Fixture fixture = Fixture.create();
        UUID domainEventId = UUID.randomUUID();
        DomainEventEnvelope<MessageCommitted> domainEvent = new DomainEventEnvelope<>(
                domainEventId,
                EventType.from("MESSAGE_COMMITTED"),
                SchemaVersion.V1,
                fixture.context().scope().organizationId(),
                Optional.of(fixture.context().scope().teamId()),
                Optional.of(fixture.context().scope().workspaceId()),
                new AggregateReference("CONVERSATION", fixture.context().conversationId().value()),
                4,
                EventActor.anonymousService(),
                fixture.context().correlationId(),
                Optional.empty(),
                Optional.empty(),
                T2,
                new MessageCommitted("message-committed"));

        RealtimeEventEnvelope<MessageCommitted> realtime = new RealtimeDomainEventProjector()
                .project(UUID.randomUUID(), StreamType.CONVERSATION, domainEvent);

        assertEquals(Optional.of(domainEventId), realtime.domainEventId());
        assertEquals(domainEvent.aggregate(), realtime.aggregate().orElseThrow());
        assertEquals(Optional.of(4L), realtime.aggregateVersion());
        assertEquals(domainEvent.correlationId(), realtime.correlationId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RealtimeDomainEventProjector()
                        .project(UUID.randomUUID(), StreamType.AG_UI, domainEvent));
    }

    @Test
    void mappedEnvelopeKeepsV1JsonCompatibleWithAdditiveFields() {
        Fixture fixture = Fixture.create();
        ConversationExecutionEventMapper.Session session = mapper.open(
                fixture.mappingContext(Optional.empty()));
        RealtimeEventEnvelope<?> envelope = session.accept(fixture.event(
                        1,
                        T0,
                        new ExecutionEventPayload.Started(ExecutionSegmentKind.RESUME)))
                .transientEvent()
                .orElseThrow();
        RealtimeEventEnvelopeJsonCodec codec =
                new RealtimeEventEnvelopeJsonCodec(new ObjectMapper());
        String json = codec.encode(envelope).replaceFirst(
                "\\{", "{\"futureOptionalField\":\"ignored\",");

        RealtimeEventEnvelope<AguiTransientPayload.RunStarted> decoded = codec.decode(
                json, AguiTransientPayload.RunStarted.class);

        assertEquals(envelope.eventId(), decoded.eventId());
        assertEquals(SchemaVersion.V1, decoded.schemaVersion());
        assertEquals(ExecutionSegmentKind.RESUME, decoded.payload().segmentKind());
    }

    private record FutureOutput(String secret) {}

    private record MessageCommitted(String messageKey) implements DomainEvent {}

    private record Fixture(
            PlatformExecutionContext context,
            UUID segmentId,
            String workProjectId) {

        private static Fixture create() {
            ConversationScope scope = new ConversationScope(
                    OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate());
            ConversationId conversationId = ConversationId.generate();
            TeamMemberId memberId = TeamMemberId.generate();
            PrincipalId userId = PrincipalId.generate();
            PrincipalId agentId = PrincipalId.generate();
            AgentRuntimeSessionId runtimeSessionId =
                    AgentRuntimeSessionId.forPersonalConversation(
                            conversationId, memberId, agentId);
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            PlatformExecutionContext context = new PlatformExecutionContext(
                    scope,
                    WorkspaceType.TEAM,
                    userId,
                    memberId,
                    Set.of(),
                    Set.of(),
                    agentId,
                    AgentProfileId.generate(),
                    1,
                    conversationId,
                    ConversationVisibility.PRIVATE,
                    ConversationParticipantId.forPrincipal(conversationId, userId),
                    ConversationParticipantId.forPrincipal(conversationId, agentId),
                    runtimeSessionId,
                    AgentScopeSessionKey.forPersonalConversation(
                            scope.organizationId(),
                            memberId,
                            agentId,
                            conversationId,
                            runtimeSessionId),
                    invocationId,
                    UUID.randomUUID(),
                    Set.of(),
                    Map.of());
            return new Fixture(context, UUID.randomUUID(), UUID.randomUUID().toString());
        }

        private ExecutionEventMappingContext mappingContext(Optional<UUID> causationId) {
            return new ExecutionEventMappingContext(context, segmentId, causationId);
        }

        private ExecutionEvent event(
                long sequence, UtcTimestamp occurredAt, ExecutionEventPayload payload) {
            return new ExecutionEvent(context.invocationId(), sequence, occurredAt, payload);
        }

        private TaskIntentV1 validTaskIntent() {
            return new TaskIntentV1(
                    "1",
                    "Implement the requested change",
                    List.of("All contract tests pass"),
                    workProjectId,
                    context.teamMemberId().toString(),
                    null,
                    null);
        }
    }
}
