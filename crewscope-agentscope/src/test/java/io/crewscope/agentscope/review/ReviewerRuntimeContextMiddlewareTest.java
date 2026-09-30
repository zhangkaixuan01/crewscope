package io.crewscope.agentscope.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileType;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Fail-closed tests for the reviewer-specific AgentScope context boundary — the chain a
 * review call can actually satisfy, unlike the conversation-semantics platform chain that
 * rejected every reviewer call with PLATFORM_CONTEXT_MISSING (defect 20, M9b-Q02).
 */
class ReviewerRuntimeContextMiddlewareTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-28T00:00:00Z");

    private final ReviewerRuntimeContextMiddleware middleware =
            new ReviewerRuntimeContextMiddleware();
    private final Coordinates coordinates = new Coordinates();
    private final TaskAgentRuntimeSession session = session(
            TaskAgentSessionPurpose.REVIEW, AgentRuntimeSessionStatus.ACTIVE,
            Optional.empty());

    @Test
    void acceptsOnlyTheExactServerDerivedReviewSessionCoordinates() {
        AtomicBoolean delegated = new AtomicBoolean();
        RuntimeContext context = context(session);

        middleware.onAgent(
                        mock(Agent.class),
                        context,
                        mock(AgentInput.class),
                        delegateOnce(delegated))
                .collectList()
                .block();

        assertTrue(delegated.get());
        assertSame(session, ReviewerRuntimeContextMiddleware.requireTrustedContext(context));
    }

    @Test
    void rejectsMissingOrTamperedReviewContextBeforeDelegation() {
        AtomicBoolean delegated = new AtomicBoolean();
        Function<AgentInput, Flux<AgentEvent>> next = delegateOnce(delegated);

        PlatformExecutionSecurityException missing = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware.onAgent(
                                mock(Agent.class),
                                context(
                                        session.agentScopeKey().userId(),
                                        session.agentScopeKey().sessionId()),
                                mock(AgentInput.class),
                                next)
                        .collectList()
                        .block());
        PlatformExecutionSecurityException tampered = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware.onAgent(
                                mock(Agent.class),
                                context(
                                        session,
                                        session.agentScopeKey().userId() + ":forged",
                                        session.agentScopeKey().sessionId()),
                                mock(AgentInput.class),
                                next)
                        .collectList()
                        .block());

        assertEquals("REVIEWER_CONTEXT_MISSING", missing.safeCode());
        assertEquals("REVIEWER_SESSION_CONTEXT_MISMATCH", tampered.safeCode());
        assertTrue(!delegated.get());
    }

    @Test
    void rejectsASpecialistPurposeOrDisabledSessionEvenWithMatchingCoordinates() {
        AtomicBoolean delegated = new AtomicBoolean();
        Function<AgentInput, Flux<AgentEvent>> next = delegateOnce(delegated);

        // A step-bound SPECIALIST session satisfies the domain shape but must still fail the
        // reviewer boundary: the REVIEW chain is not a general specialist bypass.
        TaskAgentRuntimeSession specialist = session(
                TaskAgentSessionPurpose.SPECIALIST, AgentRuntimeSessionStatus.ACTIVE,
                Optional.of(StepExecutionId.generate()));
        TaskAgentRuntimeSession disabled = session(
                TaskAgentSessionPurpose.REVIEW, AgentRuntimeSessionStatus.DISABLED,
                Optional.empty());

        PlatformExecutionSecurityException wrongPurpose = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware.onAgent(
                                mock(Agent.class),
                                context(specialist),
                                mock(AgentInput.class),
                                next)
                        .collectList()
                        .block());
        PlatformExecutionSecurityException inactive = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware.onAgent(
                                mock(Agent.class),
                                context(disabled),
                                mock(AgentInput.class),
                                next)
                        .collectList()
                        .block());

        assertEquals("REVIEWER_SESSION_PURPOSE_INVALID", wrongPurpose.safeCode());
        assertEquals("REVIEWER_SESSION_PURPOSE_INVALID", inactive.safeCode());
        assertTrue(!delegated.get());
    }

    private static Function<AgentInput, Flux<AgentEvent>> delegateOnce(AtomicBoolean delegated) {
        return ignored -> {
            delegated.set(true);
            return Flux.empty();
        };
    }

    private RuntimeContext context(TaskAgentRuntimeSession contextSession) {
        return context(contextSession,
                contextSession.agentScopeKey().userId(), contextSession.agentScopeKey().sessionId());
    }

    private RuntimeContext context(TaskAgentRuntimeSession contextSession, String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put(TaskAgentRuntimeSession.class, contextSession)
                .build();
    }

    private RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    private TaskAgentRuntimeSession session(
            TaskAgentSessionPurpose purpose,
            AgentRuntimeSessionStatus status,
            Optional<StepExecutionId> stepExecutionId) {
        AgentRuntimeSessionId id = AgentRuntimeSessionId.forTaskExecution(
                coordinates.executionId, stepExecutionId,
                coordinates.profileId, purpose.name());
        AgentScopeSessionKey key = AgentScopeSessionKey.forTaskExecution(
                coordinates.organizationId, coordinates.reviewerPrincipalId,
                coordinates.executionId, id);
        return TaskAgentRuntimeSession.reconstitute(
                id,
                coordinates.scope,
                coordinates.taskId,
                coordinates.executionId,
                stepExecutionId,
                purpose,
                coordinates.reviewerPrincipalId,
                PrincipalType.SPECIALIST_AGENT,
                coordinates.profileId,
                AgentProfileType.SPECIALIST,
                1L,
                key,
                AgentRuntimeStateReference.forSession(id),
                status,
                0L,
                AuditMetadata.createdBy(coordinates.reviewerPrincipalId, NOW));
    }

    private static final class Coordinates {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final PrincipalId reviewerPrincipalId = PrincipalId.generate();
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId, TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
    }
}
