package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Deterministic M2-S02 evidence for HarnessAgent session scheduling and cleanup. */
@Tag("integration")
class HarnessAgentM2S02ConcurrencyIntegrationTest {

    private static final String USER_ID = "member-m2-s02";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path workspace;

    @Test
    void sameSessionCallsEnterModelInSubscriptionFifoOrder() throws Exception {
        CoordinatedModel model =
                new CoordinatedModel()
                        .register("turn-one", "reply-one")
                        .register("turn-two", "reply-two")
                        .register("turn-three", "reply-three");
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        try (HarnessAgent agent = newAgent(model, stateStore)) {
            Completion first = subscribe(agent, "turn-one", context("shared-session"));
            model.turn("turn-one").awaitEntered();

            Completion second = subscribe(agent, "turn-two", context("shared-session"));
            Completion third = subscribe(agent, "turn-three", context("shared-session"));

            // Both subscriptions have been installed behind the active tail. No timing wait is
            // needed to prove that only the first turn has reached the model.
            assertFalse(model.turn("turn-two").hasEntered());
            assertFalse(model.turn("turn-three").hasEntered());

            model.turn("turn-one").complete();
            first.awaitSuccess();
            model.turn("turn-two").awaitEntered();
            assertFalse(model.turn("turn-three").hasEntered());

            model.turn("turn-two").complete();
            second.awaitSuccess();
            model.turn("turn-three").awaitEntered();
            model.turn("turn-three").complete();
            third.awaitSuccess();
        }

        assertEquals(List.of("turn-one", "turn-two", "turn-three"), model.entries());
        AgentState state = loadState(stateStore, "shared-session");
        assertEquals(6, state.getContext().size());
    }

    @Test
    void differentSessionsEnterModelBeforeEitherTurnCompletes() throws Exception {
        CoordinatedModel model =
                new CoordinatedModel()
                        .register("session-left", "left-complete")
                        .register("session-right", "right-complete");

        try (HarnessAgent agent = newAgent(model, new InMemoryAgentStateStore())) {
            Completion left = subscribe(agent, "session-left", context("left"));
            model.turn("session-left").awaitEntered();

            Completion right = subscribe(agent, "session-right", context("right"));
            model.turn("session-right").awaitEntered();

            assertEquals(List.of("session-left", "session-right"), model.entries());
            model.turn("session-left").complete();
            model.turn("session-right").complete();
            left.awaitSuccess();
            right.awaitSuccess();
        }
    }

    @Test
    void cancellingActiveTurnReleasesQueueAndDiscardsUncommittedState() throws Exception {
        CoordinatedModel model =
                new CoordinatedModel()
                        .register("cancel-me", "unused")
                        .register("run-next", "next-complete");
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();

        try (HarnessAgent agent = newAgent(model, stateStore)) {
            Completion cancelled = subscribe(agent, "cancel-me", context("cancel-session"));
            model.turn("cancel-me").awaitEntered();
            Completion next = subscribe(agent, "run-next", context("cancel-session"));
            assertFalse(model.turn("run-next").hasEntered());

            cancelled.cancel();
            model.turn("cancel-me").awaitCancelled();
            model.turn("run-next").awaitEntered();
            model.turn("run-next").complete();
            next.awaitSuccess();
        }

        List<String> persistedText = contextText(loadState(stateStore, "cancel-session"));
        assertFalse(persistedText.contains("cancel-me"));
        assertTrue(persistedText.contains("run-next"));
        assertTrue(persistedText.contains("next-complete"));
    }

    @Test
    void modelFailureReleasesQueueForNextSameSessionTurn() throws Exception {
        CoordinatedModel model =
                new CoordinatedModel()
                        .register("fail-first", "unused")
                        .register("recover-next", "recovered");

        try (HarnessAgent agent = newAgent(model, new InMemoryAgentStateStore())) {
            Completion failed = subscribe(agent, "fail-first", context("failure-session"));
            model.turn("fail-first").awaitEntered();
            Completion next = subscribe(agent, "recover-next", context("failure-session"));
            assertFalse(model.turn("recover-next").hasEntered());

            model.turn("fail-first").fail(new IllegalStateException("controlled model failure"));
            failed.awaitFailure();
            model.turn("recover-next").awaitEntered();
            model.turn("recover-next").complete();
            next.awaitSuccess();
        }
    }

    @Test
    void separateSessionTurnGateInstancesDoNotShareExecutionOwnership() throws Exception {
        SessionTurnGate firstJvm = new SessionTurnGate();
        SessionTurnGate secondJvm = new SessionTurnGate();

        firstJvm.acquire("same-owner-key");
        secondJvm.acquire("same-owner-key");
        try {
            assertTrue(firstJvm.isRunning("same-owner-key"));
            assertTrue(secondJvm.isRunning("same-owner-key"));
        } finally {
            firstJvm.release("same-owner-key");
            secondJvm.release("same-owner-key");
        }
    }

    private HarnessAgent newAgent(Model model, InMemoryAgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("crewscope-m2-s02-concurrency-agent")
                .sysPrompt("You are the deterministic CrewScope M2 session probe.")
                .model(model)
                .workspace(workspace)
                .stateStore(stateStore)
                .maxRetries(1)
                .disableCompaction()
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .enableAgentTracingLog(false)
                .build();
    }

    private static RuntimeContext context(String sessionId) {
        return RuntimeContext.builder().userId(USER_ID).sessionId(sessionId).build();
    }

    private static Completion subscribe(
            HarnessAgent agent, String text, RuntimeContext runtimeContext) {
        Completion completion = new Completion();
        Disposable disposable =
                agent.call(text, runtimeContext)
                        .subscribe(
                                ignored -> {},
                                completion::failed,
                                completion::completed);
        completion.attach(disposable);
        return completion;
    }

    private static AgentState loadState(InMemoryAgentStateStore stateStore, String sessionId) {
        return stateStore
                .get(USER_ID, sessionId, "agent_state", AgentState.class)
                .orElseThrow(() -> new AssertionError("AgentState was not persisted"));
    }

    private static List<String> contextText(AgentState state) {
        return state.getContext().stream().map(Msg::getTextContent).toList();
    }

    private static final class Completion {
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private Disposable disposable;

        void attach(Disposable disposable) {
            this.disposable = disposable;
        }

        void completed() {
            terminal.countDown();
        }

        void failed(Throwable failure) {
            failures.add(failure);
            terminal.countDown();
        }

        void cancel() {
            disposable.dispose();
        }

        void awaitSuccess() throws InterruptedException {
            awaitTerminal();
            assertTrue(failures.isEmpty(), () -> "Unexpected failure: " + failures);
        }

        void awaitFailure() throws InterruptedException {
            awaitTerminal();
            assertFalse(failures.isEmpty(), "Expected the call to fail");
        }

        private void awaitTerminal() throws InterruptedException {
            assertTrue(
                    terminal.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Agent call did not terminate before the deadlock guard");
        }
    }

    private static final class CoordinatedModel implements Model {
        private final Map<String, ControlledTurn> turns = new LinkedHashMap<>();
        private final List<String> entries = new CopyOnWriteArrayList<>();

        CoordinatedModel register(String input, String response) {
            turns.put(input, new ControlledTurn(response));
            return this;
        }

        ControlledTurn turn(String input) {
            ControlledTurn turn = turns.get(input);
            if (turn == null) {
                throw new AssertionError("Unknown controlled turn: " + input);
            }
            return turn;
        }

        List<String> entries() {
            return List.copyOf(entries);
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            String input = latestUserText(messages);
            ControlledTurn turn = turn(input);
            return Flux.defer(
                    () -> {
                        entries.add(input);
                        turn.entered.countDown();
                        return turn.terminal.asMono().flux().doOnCancel(turn.cancelled::countDown);
                    });
        }

        @Override
        public String getModelName() {
            return "crewscope-m2-s02-coordinated-model";
        }

        private static String latestUserText(List<Msg> messages) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                Msg message = messages.get(index);
                if (message.getRole() == MsgRole.USER) {
                    return message.getTextContent();
                }
            }
            throw new AssertionError("Model invocation has no user message");
        }
    }

    private static final class ControlledTurn {
        private final ChatResponse response;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final Sinks.One<ChatResponse> terminal = Sinks.one();

        ControlledTurn(String response) {
            this.response =
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(response).build()))
                            .usage(new ChatUsage(10, 4, 0.01))
                            .finishReason("stop")
                            .build();
        }

        boolean hasEntered() {
            return entered.getCount() == 0;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(
                    entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Controlled turn did not enter the model before the deadlock guard");
        }

        void awaitCancelled() throws InterruptedException {
            assertTrue(
                    cancelled.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Model publisher did not observe cancellation before the deadlock guard");
        }

        void complete() {
            assertEquals(Sinks.EmitResult.OK, terminal.tryEmitValue(response));
        }

        void fail(Throwable failure) {
            assertEquals(Sinks.EmitResult.OK, terminal.tryEmitError(failure));
        }
    }
}
