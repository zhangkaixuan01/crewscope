package io.crewscope.agentscope.teamobserver;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.crewscope.agentscope.PlatformExecutionSecurityException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Fail-closed tests for the Team Observer-specific AgentScope context boundary. */
class TeamObserverRuntimeContextMiddlewareTest {

    private final TeamObserverRuntimeContextMiddleware middleware =
            new TeamObserverRuntimeContextMiddleware();
    private final TeamObserverRuntimeSession session = session();

    @Test
    void acceptsOnlyTheExactServerDerivedObserverSessionCoordinates() {
        AtomicBoolean delegated = new AtomicBoolean();
        RuntimeContext context = context(
                session.agentScopeKey().userId(), session.agentScopeKey().sessionId(), true);

        middleware.onAgent(
                        mock(Agent.class),
                        context,
                        mock(AgentInput.class),
                        ignored -> {
                            delegated.set(true);
                            return Flux.empty();
                        })
                .collectList()
                .block();

        assertTrue(delegated.get());
        assertSame(session, TeamObserverRuntimeContextMiddleware.requireTrustedContext(context));
    }

    @Test
    void rejectsMissingOrTamperedObserverContextBeforeDelegation() {
        AtomicBoolean delegated = new AtomicBoolean();
        java.util.function.Function<AgentInput, Flux<AgentEvent>> next = ignored -> {
            delegated.set(true);
            return Flux.empty();
        };

        PlatformExecutionSecurityException missing = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware.onAgent(
                                mock(Agent.class),
                                context(
                                        session.agentScopeKey().userId(),
                                        session.agentScopeKey().sessionId(),
                                        false),
                                mock(AgentInput.class),
                                next)
                        .collectList()
                        .block());
        PlatformExecutionSecurityException tampered = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware.onAgent(
                                mock(Agent.class),
                                context(
                                        session.agentScopeKey().userId() + ":forged",
                                        session.agentScopeKey().sessionId(),
                                        true),
                                mock(AgentInput.class),
                                next)
                        .collectList()
                        .block());

        org.junit.jupiter.api.Assertions.assertEquals(
                "TEAM_OBSERVER_CONTEXT_MISSING", missing.safeCode());
        org.junit.jupiter.api.Assertions.assertEquals(
                "TEAM_OBSERVER_SESSION_CONTEXT_MISMATCH", tampered.safeCode());
        org.junit.jupiter.api.Assertions.assertFalse(delegated.get());
    }

    private RuntimeContext context(String userId, String sessionId, boolean includeSession) {
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId);
        if (includeSession) {
            builder.put(TeamObserverRuntimeSession.class, session);
        }
        return builder.build();
    }

    private static TeamObserverRuntimeSession session() {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        return new TeamObserverRuntimeSession(
                organizationId,
                teamId,
                TeamMemberId.generate(),
                TeamObserverInitialization.stablePrincipalId(teamId),
                TeamObserverInitialization.stableProfileId(teamId),
                1,
                UUID.randomUUID());
    }
}
