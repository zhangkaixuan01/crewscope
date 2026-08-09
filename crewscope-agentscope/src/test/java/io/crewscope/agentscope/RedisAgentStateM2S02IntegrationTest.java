package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import redis.clients.jedis.JedisPooled;

/** Redis Testcontainers evidence for M2-S02 process recovery and state-slot isolation. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisAgentStateM2S02IntegrationTest {

    private static final String REDIS_IMAGE = "redis:7.4-alpine";
    private static final int REDIS_PORT = 6379;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String USER_ID = "member-redis-recovery";
    private static final String SESSION_ID = "conversation-redis-recovery";

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    @TempDir Path workspace;

    @Test
    void completedStateRestoresThroughNewClientStoreAndHarnessInstance() {
        String prefix = uniquePrefix();
        RuntimeContext context = context(USER_ID, SESSION_ID);

        AgentStateStore firstStore = newStateStore(newClient(), prefix);
        try {
            try (HarnessAgent first = newAgent(new ScriptedModel("checkpoint-saved"), firstStore)) {
                Msg reply = first.call("remember checkpoint CRW-M2", context).block(TIMEOUT);
                assertEquals("checkpoint-saved", reply.getTextContent());
            }
        } finally {
            firstStore.close();
        }

        ScriptedModel restoredModel = new ScriptedModel("checkpoint-restored");
        AgentStateStore restoredStore = newStateStore(newClient(), prefix);
        try {
            try (HarnessAgent restored = newAgent(restoredModel, restoredStore)) {
                Msg reply = restored.call("restore the checkpoint", context).block(TIMEOUT);
                assertEquals("checkpoint-restored", reply.getTextContent());
            }

            String restoredInput = allText(restoredModel.request(0));
            AgentState state = loadState(restoredStore, USER_ID, SESSION_ID);
            assertTrue(restoredInput.contains("remember checkpoint CRW-M2"));
            assertTrue(restoredInput.contains("checkpoint-saved"));
            assertTrue(restoredInput.contains("restore the checkpoint"));
            assertEquals(4, state.getContext().size());
        } finally {
            restoredStore.close();
        }
    }

    @Test
    void sameSessionIdRemainsIsolatedByUserId() {
        String prefix = uniquePrefix();
        String sharedSession = "shared-session-id";
        AgentStateStore stateStore = newStateStore(newClient(), prefix);
        try {
            ScriptedModel firstModel = new ScriptedModel("user-a-private-reply");
            try (HarnessAgent first = newAgent(firstModel, stateStore)) {
                first.call("user-a-private-message", context("user-a", sharedSession))
                        .block(TIMEOUT);
            }

            ScriptedModel secondModel = new ScriptedModel("user-b-private-reply");
            try (HarnessAgent second = newAgent(secondModel, stateStore)) {
                second.call("user-b-private-message", context("user-b", sharedSession))
                        .block(TIMEOUT);
            }

            String secondInput = allText(secondModel.request(0));
            assertFalse(secondInput.contains("user-a-private-message"));
            assertFalse(secondInput.contains("user-a-private-reply"));
            assertTrue(secondInput.contains("user-b-private-message"));
            assertEquals(2, loadState(stateStore, "user-a", sharedSession).getContext().size());
            assertEquals(2, loadState(stateStore, "user-b", sharedSession).getContext().size());
        } finally {
            stateStore.close();
        }
    }

    @Test
    void interruptedInFlightTurnRecoversLastCompletedRedisCheckpoint() throws Exception {
        String prefix = uniquePrefix();
        RuntimeContext context = context(USER_ID, SESSION_ID);

        AgentStateStore interruptedProcessStore = newStateStore(newClient(), prefix);
        try {
            try (HarnessAgent first =
                    newAgent(new ScriptedModel("durable-first-reply"), interruptedProcessStore)) {
                first.call("durable-first-message", context).block(TIMEOUT);
            }

            BlockingModel interruptedModel = new BlockingModel();
            try (HarnessAgent interrupted =
                    newAgent(interruptedModel, interruptedProcessStore)) {
                Disposable invocation =
                        interrupted.call("unfinished-second-message", context).subscribe();
                interruptedModel.awaitEntered();

                // Disposing before a model result simulates loss of the in-flight process before
                // AgentScope reaches its successful whole-state save checkpoint.
                invocation.dispose();
                interruptedModel.awaitCancelled();
            }

            List<String> checkpointText =
                    contextText(loadState(interruptedProcessStore, USER_ID, SESSION_ID));
            assertTrue(checkpointText.contains("durable-first-message"));
            assertTrue(checkpointText.contains("durable-first-reply"));
            assertFalse(checkpointText.contains("unfinished-second-message"));
        } finally {
            interruptedProcessStore.close();
        }

        ScriptedModel recoveryModel = new ScriptedModel("continued-after-restart");
        AgentStateStore recoveryProcessStore = newStateStore(newClient(), prefix);
        try {
            try (HarnessAgent recovered = newAgent(recoveryModel, recoveryProcessStore)) {
                recovered.call("continue-after-restart", context).block(TIMEOUT);
            }

            String recoveryInput = allText(recoveryModel.request(0));
            assertTrue(recoveryInput.contains("durable-first-message"));
            assertTrue(recoveryInput.contains("durable-first-reply"));
            assertTrue(recoveryInput.contains("continue-after-restart"));
            assertFalse(recoveryInput.contains("unfinished-second-message"));
        } finally {
            recoveryProcessStore.close();
        }
    }

    private HarnessAgent newAgent(Model model, AgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("crewscope-m2-s02-redis-agent")
                .sysPrompt("You are the deterministic CrewScope Redis recovery probe.")
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

    private static AgentStateStore newStateStore(JedisPooled client, String prefix) {
        return RedisDistributedStore.fromJedis(client, prefix).agentStateStore();
    }

    private static JedisPooled newClient() {
        return new JedisPooled(REDIS.getHost(), REDIS.getFirstMappedPort());
    }

    private static RuntimeContext context(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static AgentState loadState(
            AgentStateStore stateStore, String userId, String sessionId) {
        return stateStore
                .get(userId, sessionId, "agent_state", AgentState.class)
                .orElseThrow(() -> new AssertionError("AgentState was not persisted"));
    }

    private static String uniquePrefix() {
        return "crewscope:test:m2-s02:" + UUID.randomUUID() + ":";
    }

    private static String allText(List<Msg> messages) {
        return messages.stream().map(Msg::getTextContent).reduce("", (left, right) -> left + right);
    }

    private static List<String> contextText(AgentState state) {
        return state.getContext().stream().map(Msg::getTextContent).toList();
    }

    private static final class BlockingModel implements Model {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final Sinks.One<ChatResponse> response = Sinks.one();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(
                    () -> {
                        entered.countDown();
                        return response.asMono().flux().doOnCancel(cancelled::countDown);
                    });
        }

        @Override
        public String getModelName() {
            return "crewscope-m2-s02-blocking-model";
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(
                    entered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Interrupted turn did not enter the model before the deadlock guard");
        }

        void awaitCancelled() throws InterruptedException {
            assertTrue(
                    cancelled.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "Interrupted turn did not observe cancellation before the deadlock guard");
        }
    }
}
