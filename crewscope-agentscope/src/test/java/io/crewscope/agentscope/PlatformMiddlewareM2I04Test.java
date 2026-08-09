package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.message.ToolUseBlock;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.provider.ProviderType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** M2-I04 evidence for pre-model security checks, typed Tool context and safe audit records. */
class PlatformMiddlewareM2I04Test {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-09T07:00:00Z"), ZoneOffset.UTC);

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void typedContextIsVisibleToMiddlewareAndToolExecutionContext() {
        Fixture fixture = Fixture.create();
        AtomicBoolean delegated = new AtomicBoolean();

        new PlatformRuntimeContextMiddleware()
                .onAgent(
                        null,
                        fixture.runtimeContext,
                        new AgentInput(List.of()),
                        ignored -> {
                            delegated.set(true);
                            return Flux.empty();
                        })
                .blockLast();

        assertTrue(delegated.get());
        assertSame(
                fixture.platformContext,
                fixture.runtimeContext.get(PlatformExecutionContext.class));
        assertSame(
                fixture.platformContext,
                fixture.runtimeContext
                        .asToolExecutionContext()
                        .get(PlatformExecutionContext.class));
    }

    @Test
    void rejectsMissingOrMismatchedContextBeforeDelegation() {
        Fixture fixture = Fixture.create();
        AtomicBoolean delegated = new AtomicBoolean();
        PlatformRuntimeContextMiddleware middleware = new PlatformRuntimeContextMiddleware();

        PlatformExecutionSecurityException missing = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware
                        .onAgent(
                                null,
                                RuntimeContext.builder()
                                        .userId(fixture.session.agentScopeKey().userId())
                                        .sessionId(fixture.session.agentScopeKey().sessionId())
                                        .build(),
                                new AgentInput(List.of()),
                                ignored -> {
                                    delegated.set(true);
                                    return Flux.empty();
                                })
                        .blockLast());
        PlatformExecutionSecurityException mismatch = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> middleware
                        .onAgent(
                                null,
                                RuntimeContext.builder()
                                        .userId("client-overridden-user")
                                        .sessionId(fixture.session.agentScopeKey().sessionId())
                                        .put(PlatformExecutionContext.class, fixture.platformContext)
                                        .build(),
                                new AgentInput(List.of()),
                                ignored -> {
                                    delegated.set(true);
                                    return Flux.empty();
                                })
                        .blockLast());

        assertEquals("PLATFORM_CONTEXT_MISSING", missing.safeCode());
        assertEquals("AGENTSCOPE_SESSION_CONTEXT_MISMATCH", mismatch.safeCode());
        assertFalse(delegated.get());
    }

    @Test
    void rejectsMissingRequiredBindingBeforeModelExecution() {
        Fixture fixture = Fixture.create();
        PlatformExecutionContext missingBinding = fixture.withRequiredProvider(
                ProviderType.SOURCE_CODE);
        RuntimeContext runtimeContext = fixture.runtimeContext(missingBinding);
        AtomicBoolean delegated = new AtomicBoolean();

        PlatformExecutionSecurityException failure = assertThrows(
                PlatformExecutionSecurityException.class,
                () -> new ProviderBindingSecurityMiddleware()
                        .onAgent(
                                null,
                                runtimeContext,
                                new AgentInput(List.of()),
                                ignored -> {
                                    delegated.set(true);
                                    return Flux.empty();
                                })
                        .blockLast());

        assertEquals("REQUIRED_PROVIDER_BINDING_MISSING", failure.safeCode());
        assertFalse(delegated.get());
    }

    @Test
    void auditsSuccessFailureCancellationAndOnlyToolNames() {
        Fixture fixture = Fixture.create();
        List<AgentExecutionAuditRecord> records = new ArrayList<>();
        PlatformAuditMiddleware middleware = new PlatformAuditMiddleware(records::add, CLOCK);

        middleware.onAgent(
                        null,
                        fixture.runtimeContext,
                        new AgentInput(List.of()),
                        ignored -> Flux.empty())
                .blockLast();
        assertThrows(
                IllegalStateException.class,
                () -> middleware
                        .onAgent(
                                null,
                                fixture.runtimeContext,
                                new AgentInput(List.of()),
                                ignored -> Flux.error(new IllegalStateException(
                                        "sensitive prompt must not enter audit")))
                        .blockLast());
        ToolUseBlock tool = ToolUseBlock.builder()
                .id("tool-1")
                .name("github_read_repository")
                .input(Map.of("secret", "must-not-enter-audit"))
                .build();
        middleware.onActing(
                        null,
                        fixture.runtimeContext,
                        new ActingInput(List.of(tool)),
                        ignored -> Flux.empty())
                .blockLast();
        Disposable canceled = middleware.onAgent(
                        null,
                        fixture.runtimeContext,
                        new AgentInput(List.of()),
                        ignored -> Flux.never())
                .subscribe();
        canceled.dispose();

        assertTrue(records.stream().anyMatch(record ->
                record.phase() == AgentExecutionAuditPhase.INVOCATION
                        && record.outcome() == AgentExecutionAuditOutcome.COMPLETED));
        assertTrue(records.stream().anyMatch(record ->
                record.outcome() == AgentExecutionAuditOutcome.FAILED
                        && record.safeFailureType().equals(Optional.of("EXECUTION_FAILED"))));
        assertTrue(records.stream().anyMatch(record ->
                record.outcome() == AgentExecutionAuditOutcome.CANCELED));
        assertTrue(records.stream().anyMatch(record ->
                record.phase() == AgentExecutionAuditPhase.TOOL_EXECUTION
                        && record.toolNames().equals(Set.of("github_read_repository"))));
        assertFalse(records.toString().contains("must-not-enter-audit"));

        AgentExecutionAuditRecord sanitized = AgentExecutionAuditRecord.from(
                CLOCK.instant(),
                fixture.platformContext,
                AgentExecutionAuditPhase.TOOL_EXECUTION,
                AgentExecutionAuditOutcome.FAILED,
                Set.of("github\nsecret"),
                1,
                Optional.of("TOOL_EXECUTION_FAILED"));
        assertEquals(Set.of("unknown_tool"), sanitized.toolNames());
        assertFalse(sanitized.toString().contains("github\nsecret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentExecutionAuditRecord.from(
                        CLOCK.instant(),
                        fixture.platformContext,
                        AgentExecutionAuditPhase.TOOL_EXECUTION,
                        AgentExecutionAuditOutcome.FAILED,
                        Set.of(),
                        1,
                        Optional.of("unsafe failure\ncontent")));
    }

    @Test
    void auditSinkFailureStopsTheInterceptedExecution() {
        Fixture fixture = Fixture.create();
        AtomicBoolean delegated = new AtomicBoolean();
        PlatformAuditMiddleware middleware = new PlatformAuditMiddleware(
                ignored -> {
                    throw new IllegalStateException("audit unavailable");
                },
                CLOCK);

        assertThrows(
                IllegalStateException.class,
                () -> middleware
                        .onAgent(
                                null,
                                fixture.runtimeContext,
                                new AgentInput(List.of()),
                                ignored -> {
                                    delegated.set(true);
                                    return Flux.empty();
                                })
                        .blockLast());
        assertFalse(delegated.get());
    }

    @Test
    void preservesTheSecurityAndAuditMiddlewareOrder() {
        PlatformRuntimeContextMiddleware context = new PlatformRuntimeContextMiddleware();
        ProviderBindingSecurityMiddleware binding = new ProviderBindingSecurityMiddleware();
        PlatformAuditMiddleware audit = new PlatformAuditMiddleware(ignored -> {}, CLOCK);

        PlatformAgentMiddlewareSet set = new PlatformAgentMiddlewareSet(context, binding, audit);

        assertEquals(List.of(context, binding, audit), set.ordered());
    }

    private record Fixture(
            AgentRuntimeSession session,
            PlatformExecutionContext platformContext,
            RuntimeContext runtimeContext) {

        private static Fixture create() {
            AgentRuntimeSession session = AgentScopeRuntimeTestFixture.session(
                    io.crewscope.domain.workspace.AgentProfileId.generate(), 1);
            RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
            UUID correlationId = UUID.randomUUID();
            PlatformExecutionContext context = AgentScopeRuntimeTestFixture.platformContext(
                    session, invocationId, correlationId);
            RuntimeContext runtime = runtimeContext(session, context);
            return new Fixture(session, context, runtime);
        }

        private PlatformExecutionContext withRequiredProvider(ProviderType providerType) {
            return new PlatformExecutionContext(
                    platformContext.scope(),
                    platformContext.workspaceType(),
                    platformContext.requestPrincipalId(),
                    platformContext.teamMemberId(),
                    platformContext.teamRoleKeys(),
                    platformContext.teamPermissions(),
                    platformContext.personalAgentPrincipalId(),
                    platformContext.agentProfileId(),
                    platformContext.agentProfileVersion(),
                    platformContext.conversationId(),
                    platformContext.conversationVisibility(),
                    platformContext.userParticipantId(),
                    platformContext.agentParticipantId(),
                    platformContext.runtimeSessionId(),
                    platformContext.agentScopeSessionKey(),
                    platformContext.invocationId(),
                    platformContext.correlationId(),
                    Set.of(providerType),
                    Map.of());
        }

        private RuntimeContext runtimeContext(PlatformExecutionContext context) {
            return runtimeContext(session, context);
        }

        private static RuntimeContext runtimeContext(
                AgentRuntimeSession session, PlatformExecutionContext context) {
            return RuntimeContext.builder()
                    .userId(session.agentScopeKey().userId())
                    .sessionId(session.agentScopeKey().sessionId())
                    .put(PlatformExecutionContext.class, context)
                    .build();
        }
    }
}
