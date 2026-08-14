package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunAndInterruptTest {

    private static final UtcTimestamp T1 = UtcTimestamp.parse("2026-08-13T10:11:00Z");
    private static final UtcTimestamp T2 = UtcTimestamp.parse("2026-08-13T10:12:00Z");
    private static final UtcTimestamp T3 = UtcTimestamp.parse("2026-08-13T10:13:00Z");
    private static final UtcTimestamp T4 = UtcTimestamp.parse("2026-08-13T10:14:00Z");

    @Test
    void supportsMultipleRunsForOneStepWithMonotonicRunAndSegmentSequences() {
        TaskAgentRuntimeSessionTest.RuntimeFixture fixture =
                new TaskAgentRuntimeSessionTest.RuntimeFixture();
        TaskAgentRuntimeSession session = fixture.stepSession();

        AgentRun first = AgentRun.start(
                        AgentRunId.generate(), session, 1, fixture.planning.base.executor, T1)
                .cancel(0, fixture.planning.base.owner, T2);
        AgentRun second = AgentRun.start(
                AgentRunId.generate(), session, 2, fixture.planning.base.executor, T2);

        assertEquals(fixture.step.id(), first.stepExecutionId().orElseThrow());
        assertEquals(1, first.runSequence());
        assertEquals(2, second.runSequence());
        assertEquals(1, first.currentSegment().sequence());
    }

    @Test
    void interruptsAndResumesSameLogicalRunIdempotently() {
        TaskAgentRuntimeSessionTest.RuntimeFixture fixture =
                new TaskAgentRuntimeSessionTest.RuntimeFixture();
        AgentRun running = AgentRun.start(
                AgentRunId.generate(),
                fixture.stepSession(),
                1,
                fixture.planning.base.executor,
                T1);
        AgentInterrupt pending = AgentInterrupt.open(
                AgentInterruptId.generate(),
                running,
                AgentInterruptKind.CLARIFICATION,
                RuntimeContentHash.sha256("opaque-interrupt-token"),
                fixture.planning.base.executor,
                T1);
        AgentRun interrupted = running.interrupt(
                pending, 0, fixture.planning.base.executor, T2);
        UUID resumeRequestId = UUID.randomUUID();
        RuntimeContentHash answerHash = RuntimeContentHash.sha256("approved-answer");
        AgentInterrupt resolved = pending.resolve(
                resumeRequestId, answerHash, 0, fixture.planning.base.owner, T2);
        AgentInterrupt exactRetry = resolved.resolve(
                resumeRequestId, answerHash, 0, fixture.planning.base.owner, T3);
        AgentRun resumed = interrupted.resume(
                resolved, 1, fixture.planning.base.owner, T3);
        AgentRun resumedRetry = resumed.resume(
                resolved, 1, fixture.planning.base.owner, T4);

        assertSame(resolved, exactRetry);
        assertSame(resumed, resumedRetry);
        assertEquals(AgentRunStatus.RUNNING, resumed.status());
        assertEquals(2, resumed.currentSegment().sequence());
        assertEquals(AgentRunSegmentKind.RESUME, resumed.currentSegment().kind());
        assertEquals(pending.id(), resumed.currentSegment().resumedFromInterruptId().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> resolved.resolve(
                        resumeRequestId,
                        RuntimeContentHash.sha256("different-answer"),
                        1,
                        fixture.planning.base.owner,
                        T4));
    }

    @Test
    void commitsOneImmutableTerminalWithArtifactReferenceOnly() {
        TaskAgentRuntimeSessionTest.RuntimeFixture fixture =
                new TaskAgentRuntimeSessionTest.RuntimeFixture();
        AgentRun running = AgentRun.start(
                AgentRunId.generate(),
                fixture.stepSession(),
                1,
                fixture.planning.base.executor,
                T1);
        RuntimeArtifact result = RuntimeArtifact.register(
                RuntimeArtifactId.generate(),
                ArtifactId.generate(),
                running,
                RuntimeArtifactKind.MODEL_RESULT,
                "application/json",
                32_000,
                RuntimeContentHash.sha256("large-result-bytes"),
                Optional.empty(),
                fixture.planning.base.executor,
                T2);

        AgentRun completed = running.complete(
                Optional.of(result), 0, fixture.planning.base.executor, T3);

        assertTrue(completed.status().isTerminal());
        assertEquals(result.id(), completed.terminal().orElseThrow().resultArtifactId().orElseThrow());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> completed.cancel(1, fixture.planning.base.owner, T4));
    }

    @Test
    void recordsContinuityGapOnlyOnImmediateRecoveryRun() {
        TaskAgentRuntimeSessionTest.RuntimeFixture fixture =
                new TaskAgentRuntimeSessionTest.RuntimeFixture();
        TaskAgentRuntimeSession session = fixture.stepSession();
        AgentRun previous = AgentRun.start(
                        AgentRunId.generate(), session, 1, fixture.planning.base.executor, T1)
                .fail(
                        "STATE_LOST",
                        Optional.empty(),
                        0,
                        fixture.planning.base.executor,
                        T2);
        AgentRunContinuityGap gap = new AgentRunContinuityGap(
                previous.id(),
                Optional.empty(),
                4,
                6,
                AgentRunContinuityGapReason.REDIS_STATE_LOST,
                T3);

        AgentRun recovered = AgentRun.recover(
                AgentRunId.generate(),
                session,
                previous,
                gap,
                2,
                fixture.planning.base.executor,
                T3);

        assertEquals(AgentRunSegmentKind.RECOVERY, recovered.currentSegment().kind());
        assertEquals(gap, recovered.continuityGap().orElseThrow());
        assertThrows(
                DomainValidationException.class,
                () -> AgentRun.recover(
                        AgentRunId.generate(),
                        session,
                        previous,
                        gap,
                        3,
                        fixture.planning.base.executor,
                        T4));
    }
}
