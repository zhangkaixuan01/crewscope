package io.crewscope.agentscope.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.agentscope.distiller.DistillerKind;
import io.crewscope.agentscope.distiller.DistillerRuntimeSession;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** M10-A02b trusted-boundary middleware and deterministic Distiller session identity. */
class KnowledgeDistillerRuntimeContextMiddlewareTest {

    private final KnowledgeDistillerRuntimeContextMiddleware middleware =
            new KnowledgeDistillerRuntimeContextMiddleware();

    @Test
    void passesTheSessionThroughWhenTheContextMatchesExactly() {
        DistillerRuntimeSession session = session(TeamId.generate());

        RuntimeContext context = context(
                session.agentScopeKey().userId(), session.agentScopeKey().sessionId(), session);

        assertEquals(session,
                KnowledgeDistillerRuntimeContextMiddleware.requireTrustedContext(context));
        apply(context).blockLast();
    }

    @Test
    void deniesMissingSessionsAndMismatchedAgentScopeCoordinates() {
        DistillerRuntimeSession session = session(TeamId.generate());
        RuntimeContext empty = RuntimeContext.builder()
                .userId(session.agentScopeKey().userId())
                .sessionId(session.agentScopeKey().sessionId())
                .build();

        assertEquals(
                "KNOWLEDGE_DISTILLER_CONTEXT_MISSING",
                assertThrows(PlatformExecutionSecurityException.class,
                        () -> KnowledgeDistillerRuntimeContextMiddleware
                                .requireTrustedContext(empty)).safeCode());

        RuntimeContext stranger = context(
                "crewscope:v1:user:stranger", session.agentScopeKey().sessionId(), session);
        assertEquals(
                "KNOWLEDGE_DISTILLER_SESSION_CONTEXT_MISMATCH",
                assertThrows(PlatformExecutionSecurityException.class,
                        () -> KnowledgeDistillerRuntimeContextMiddleware
                                .requireTrustedContext(stranger)).safeCode());
        assertEquals(
                "KNOWLEDGE_DISTILLER_SESSION_CONTEXT_MISMATCH",
                assertThrows(PlatformExecutionSecurityException.class,
                        () -> apply(stranger).blockLast()).safeCode());
    }

    @Test
    void sessionIdentityBindsToTheTeamsDeterministicDistillerPair() {
        TeamId teamId = TeamId.generate();
        DistillerRuntimeSession session = session(teamId);

        // Another Team's stable Profile id: the deterministic pair binding must reject it.
        assertThrows(IllegalArgumentException.class, () -> new DistillerRuntimeSession(
                DistillerKind.KNOWLEDGE,
                session.organizationId(),
                session.teamId(),
                session.initiatedBy(),
                session.distillerPrincipalId(),
                KnowledgeDistillerInitialization.stableProfileId(TeamId.generate()),
                session.distillerProfileVersion(),
                UUID.randomUUID()));

        DistillerRuntimeSession replay = new DistillerRuntimeSession(
                DistillerKind.KNOWLEDGE,
                session.organizationId(),
                session.teamId(),
                session.initiatedBy(),
                session.distillerPrincipalId(),
                session.distillerProfileId(),
                session.distillerProfileVersion(),
                UUID.randomUUID());
        // Same Team/initiator identity, distinct per-command session and state slot.
        assertEquals(session.agentScopeKey().userId(), replay.agentScopeKey().userId());
        assertNotEquals(session.agentScopeKey().sessionId(), replay.agentScopeKey().sessionId());
        assertNotEquals(session.stateReference(), replay.stateReference());
    }

    private Flux<AgentEvent> apply(RuntimeContext context) {
        return middleware.onAgent(
                mock(Agent.class),
                context,
                mock(AgentInput.class),
                input -> Flux.<AgentEvent>empty());
    }

    private static RuntimeContext context(
            String userId, String sessionId, DistillerRuntimeSession slot) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .put(DistillerRuntimeSession.class, slot)
                .build();
    }

    private static DistillerRuntimeSession session(TeamId teamId) {
        return new DistillerRuntimeSession(
                DistillerKind.KNOWLEDGE,
                OrganizationId.generate(),
                teamId,
                PrincipalId.generate(),
                KnowledgeDistillerInitialization.stablePrincipalId(teamId),
                KnowledgeDistillerInitialization.stableProfileId(teamId),
                3L,
                UUID.randomUUID());
    }
}
