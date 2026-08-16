package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * M4-S01 evidence for the safe AgentScope coding composition.
 *
 * <p>The probe deliberately registers only narrow CrewScope tools. AgentScope owns the reasoning,
 * plan, todo, compaction, interrupt/resume and sandbox plumbing; CrewScope owns the repository and
 * command policy presented to the model.
 */
@Tag("docker")
@Tag("integration")
class HarnessAgentM4S01CodingCompositionIntegrationTest {

  private static final String IMAGE =
      "maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4";
  private static final String USER_ID = "member-m4-s01";
  private static final String SESSION_ID = "task-execution-m4-s01";
  private static final String SOURCE_FILE = "src/main/java/io/crewscope/probe/Greeting.java";
  private static final String SANDBOX_SOURCE = "/workspace/repository/" + SOURCE_FILE;
  private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(3);

  @TempDir Path hostWorkspace;

  @Test
  void controlledCodingAgentPlansPausesResumesChangesAndTestsInDocker() throws Exception {
    Assumptions.assumeTrue(commandSucceeds("docker", "info"), "Docker daemon is required");
    Assumptions.assumeTrue(
        commandSucceeds("docker", "image", "inspect", IMAGE),
        "Required image is missing; run: docker pull " + IMAGE);

    Path repository = initializeFixtureRepository();
    TrackingExecutionGuard guard = new TrackingExecutionGuard();
    AtomicReference<AbstractFilesystem> filesystem = new AtomicReference<>();
    ControlledRepositoryReadTool readTool = new ControlledRepositoryReadTool(filesystem);
    ControlledApplyChangeTool changeTool = new ControlledApplyChangeTool(filesystem);
    ControlledAcceptanceTool acceptanceTool = new ControlledAcceptanceTool(filesystem);
    Toolkit controlledToolkit = new Toolkit();
    controlledToolkit.registerAgentTool(readTool);
    controlledToolkit.registerAgentTool(changeTool);
    controlledToolkit.registerAgentTool(acceptanceTool);

    ScriptedModel compactionModel = repeatedModel("M4-S01 compacted checkpoint", 20);
    ScriptedModel codingModel =
        new ScriptedModel(
            toolResponse(
                "todo-start",
                "todo_write",
                Map.of(
                    "todos",
                    List.of(
                        todo("Inspect and plan", "in_progress"),
                        todo("Apply controlled change", "pending"),
                        todo("Run acceptance test", "pending")))),
            toolResponse("plan-enter", "plan_enter", Map.of()),
            toolResponse("read-target", ControlledRepositoryReadTool.NAME, Map.of()),
            // PlanModeMiddleware must deny this invocation before the tool body runs.
            toolResponse("blocked-change", ControlledApplyChangeTool.NAME, Map.of()),
            toolResponse(
                "plan-write",
                "plan_write",
                Map.of(
                    "content",
                    "# M4-S01 plan\n\n"
                        + "1. Read the target.\n"
                        + "2. Apply the fixed change.\n"
                        + "3. Run acceptance.")),
            toolResponse(
                "plan-exit",
                "plan_exit",
                Map.of("summary", "Apply the controlled change and validate it")),
            toolResponse("apply-change", ControlledApplyChangeTool.NAME, Map.of()),
            toolResponse("run-acceptance", ControlledAcceptanceTool.NAME, Map.of()),
            toolResponse(
                "todo-complete",
                "todo_write",
                Map.of(
                    "todos",
                    List.of(
                        todo("Inspect and plan", "completed"),
                        todo("Apply controlled change", "completed"),
                        todo("Run acceptance test", "completed")))),
            textResponse("m4-s01-controlled-coding-complete"));

    RuntimeContext runtimeContext =
        RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    Msg resumed;
    try (HarnessAgent agent =
        newSandboxAgent(
            codingModel,
            compactionModel,
            controlledToolkit,
            repository,
            hostUser(repository),
            guard)) {
      filesystem.set(agent.getWorkspaceManager().getFilesystem());
      assertSafeToolSurface(agent.getToolkit().getToolNames());
      assertNotNull(agent.getCompactionHook());

      Msg interrupted =
          agent
              .call("Plan and implement the deterministic coding change", runtimeContext)
              .block(AGENT_TIMEOUT);
      ToolUseBlock pendingPlanExit = onlyPendingToolCall(interrupted, "plan_exit");

      assertEquals(1, readTool.executionCount());
      assertEquals(0, changeTool.executionCount());
      assertEquals(0, acceptanceTool.executionCount());
      assertTrue(Files.readString(repository.resolve(SOURCE_FILE)).contains("before-m4"));
      assertTrue(agent.isPlanModeActive(runtimeContext));

      resumed =
          agent.call(List.of(confirmMessage(pendingPlanExit)), runtimeContext).block(AGENT_TIMEOUT);

      assertFalse(agent.isPlanModeActive(runtimeContext));
      assertEquals(
          3,
          agent
              .getDelegate()
              .getAgentState(USER_ID, SESSION_ID)
              .getTasksContext()
              .getTasks()
              .stream()
              .filter(task -> task.getState().name().equals("COMPLETED"))
              .count());
    }

    assertNotNull(resumed);
    assertEquals("m4-s01-controlled-coding-complete", resumed.getTextContent());
    assertEquals(1, changeTool.executionCount());
    assertEquals(1, acceptanceTool.executionCount());
    assertTrue(compactionModel.callCount() > 0, "Compaction must run during the long tool loop");
    assertEquals(2, guard.enterCount());
    assertEquals(2, guard.closeCount());
    assertEquals(Set.of(SESSION_ID), Set.copyOf(guard.keys()));

    String source = Files.readString(repository.resolve(SOURCE_FILE));
    assertTrue(source.contains("after-m4"));
    assertEquals(
        SOURCE_FILE,
        runHost(repository, "git", "diff", "--name-only").requireSuccess().output().strip());
    assertTrue(
        runHost(repository, "git", "diff", "--", SOURCE_FILE)
            .output()
            .contains("+        return \"after-m4\";"));
  }

  private HarnessAgent newSandboxAgent(
      ScriptedModel codingModel,
      ScriptedModel compactionModel,
      Toolkit toolkit,
      Path repository,
      String hostUser,
      SandboxExecutionGuard guard)
      throws IOException {
    BindMountEntry worktreeMount = new BindMountEntry();
    worktreeMount.setHostPath(repository.toRealPath().toString());
    worktreeMount.setReadOnly(false);

    WorkspaceSpec workspaceSpec = new WorkspaceSpec();
    workspaceSpec.setRoot("/workspace");
    workspaceSpec.setEntries(Map.of("repository", worktreeMount));

    DockerFilesystemSpec filesystemSpec =
        new DockerFilesystemSpec()
            .image(IMAGE)
            .workspaceRoot("/workspace")
            .environment(Map.of("HOME", "/tmp", "MAVEN_CONFIG", "/tmp/.m2"))
            .additionalRunArgs("--user", hostUser)
            .network("none")
            .workspaceSpec(workspaceSpec);
    filesystemSpec.isolationScope(IsolationScope.SESSION);
    filesystemSpec.executionGuard(guard);

    PermissionContextState permissions =
        PermissionContextState.builder()
            .mode(PermissionMode.BYPASS)
            // Keep deterministic fixture tools automatic while preserving an explicit
            // durable pause at the PLAN -> BUILD hand-off.
            .addAskRule(
                "plan_exit",
                new PermissionRule("plan_exit", null, PermissionBehavior.ASK, "m4-s01-checkpoint"))
            .build();

    HarnessAgent agent =
        HarnessAgent.builder()
            .name("crewscope-m4-s01-coding-agent")
            .agentId("crewscope-m4-s01-coding-agent")
            .sysPrompt("Use only the registered CrewScope coding tools and finish the task.")
            .model(codingModel)
            .toolkit(toolkit)
            .workspace(hostWorkspace)
            .filesystem(filesystemSpec)
            .stateStore(new InMemoryAgentStateStore())
            .permissionContext(permissions)
            .enableTaskList()
            .enablePlanMode()
            .compaction(
                CompactionConfig.builder()
                    .model(compactionModel)
                    .triggerMessages(6)
                    .triggerTokens(Integer.MAX_VALUE)
                    .keepMessages(2)
                    .keepTokens(0)
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(false)
                    .prune(null)
                    .build())
            .disableFilesystemTools()
            .disableShellTool()
            .disableSubagents()
            .disableDynamicSubagents()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableToolsConfig()
            .enableAgentTracingLog(false)
            .build();
    // Harness creates a workspace MessageBus and its wait tool even when async tools and
    // subagents are disabled. M4 removes that unrelated capability from the frozen surface.
    agent.getToolkit().removeTool("wait_async_results");
    return agent;
  }

  private Path initializeFixtureRepository() throws IOException, InterruptedException {
    Path repository = Files.createDirectories(hostWorkspace.resolve("repository"));
    Path source = repository.resolve(SOURCE_FILE);
    Files.createDirectories(source.getParent());
    Files.writeString(
        repository.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <modelVersion>4.0.0</modelVersion>
            <groupId>io.crewscope.probe</groupId>
            <artifactId>m4-s01-fixture</artifactId>
            <version>1.0.0</version>
        </project>
        """,
        StandardCharsets.UTF_8);
    Files.writeString(repository.resolve(".gitignore"), "target/\n", StandardCharsets.UTF_8);
    Files.writeString(
        source,
        """
        package io.crewscope.probe;

        public final class Greeting {

            private Greeting() {}

            public static String message() {
                return "before-m4";
            }
        }
        """,
        StandardCharsets.UTF_8);

    runHost(repository, "git", "init", "--initial-branch=main").requireSuccess();
    runHost(repository, "git", "config", "user.name", "CrewScope M4 Probe").requireSuccess();
    runHost(repository, "git", "config", "user.email", "m4-probe@crewscope.local").requireSuccess();
    runHost(repository, "git", "add", ".").requireSuccess();
    runHost(repository, "git", "commit", "-m", "initial fixture").requireSuccess();
    return repository;
  }

  private static void assertSafeToolSurface(Set<String> toolNames) {
    assertEquals(
        Set.of(
            ControlledRepositoryReadTool.NAME,
            ControlledApplyChangeTool.NAME,
            ControlledAcceptanceTool.NAME,
            "todo_write",
            "plan_enter",
            "plan_write",
            "plan_exit"),
        toolNames);
    assertTrue(
        Set.of(
                "execute",
                "read_file",
                "write_file",
                "edit_file",
                "grep_files",
                "glob_files",
                "list_files",
                "agent_generate",
                "agent_spawn",
                "agent_send",
                "agent_list",
                "load_skill_through_path",
                "skill_manage",
                "propose_skill")
            .stream()
            .noneMatch(toolNames::contains),
        toolNames.toString());
  }

  private static ToolUseBlock onlyPendingToolCall(Msg result, String expectedName) {
    assertNotNull(result);
    List<ToolUseBlock> calls = result.getContentBlocks(ToolUseBlock.class);
    assertEquals(1, calls.size());
    assertEquals(expectedName, calls.get(0).getName());
    assertEquals(ToolCallState.ASKING, calls.get(0).getState());
    return calls.get(0);
  }

  private static Msg confirmMessage(ToolUseBlock pending) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(Msg.METADATA_CONFIRM_RESULTS, List.of(new ConfirmResult(true, pending)));
    return Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .textContent("[approve coding plan]")
        .metadata(metadata)
        .build();
  }

  private static Map<String, Object> todo(String content, String status) {
    return Map.of("content", content, "status", status, "priority", "high");
  }

  private static ChatResponse toolResponse(
      String callId, String toolName, Map<String, Object> input) {
    return ChatResponse.builder()
        .content(
            List.of(
                ToolUseBlock.builder()
                    .id(callId)
                    .name(toolName)
                    .input(input)
                    .content(JsonUtils.getJsonCodec().toJson(input))
                    .build()))
        .usage(new ChatUsage(12, 8, 0.01))
        .build();
  }

  private static ChatResponse textResponse(String text) {
    return ChatResponse.builder()
        .content(List.of(TextBlock.builder().text(text).build()))
        .usage(new ChatUsage(8, 4, 0.01))
        .finishReason("stop")
        .build();
  }

  private static ScriptedModel repeatedModel(String response, int count) {
    String[] responses = new String[count];
    java.util.Arrays.fill(responses, response);
    return new ScriptedModel(responses);
  }

  private static String hostUser(Path workingDirectory) throws IOException, InterruptedException {
    String userId = runHost(workingDirectory, "id", "-u").requireSuccess().output().strip();
    String groupId = runHost(workingDirectory, "id", "-g").requireSuccess().output().strip();
    if (!userId.matches("[0-9]+") || !groupId.matches("[0-9]+")) {
      throw new IllegalStateException("Host UID and GID must be numeric");
    }
    return userId + ":" + groupId;
  }

  private static boolean commandSucceeds(String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      boolean completed = process.waitFor(10, TimeUnit.SECONDS);
      if (!completed) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException exception) {
      return false;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static ProcessResult runHost(Path workingDirectory, String... command)
      throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();
    boolean completed = process.waitFor(30, TimeUnit.SECONDS);
    if (!completed) {
      process.destroyForcibly();
      throw new IllegalStateException("Host command timed out: " + String.join(" ", command));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.exitValue(), output);
  }

  private static ToolResultBlock result(ToolCallParam param, String text) {
    return ToolResultBlock.text(text)
        .withIdAndName(param.getToolUseBlock().getId(), param.getToolUseBlock().getName());
  }

  private abstract static class ControlledTool extends ToolBase {

    private final AtomicReference<AbstractFilesystem> filesystem;
    private final AtomicInteger executionCount = new AtomicInteger();

    private ControlledTool(
        String name,
        String description,
        boolean readOnly,
        AtomicReference<AbstractFilesystem> filesystem) {
      super(
          ToolBase.builder()
              .name(name)
              .description(description)
              .inputSchema(Map.of("type", "object", "properties", Map.of()))
              .readOnly(readOnly)
              .concurrencySafe(false));
      this.filesystem = filesystem;
    }

    protected final AbstractFilesystem filesystem() {
      return java.util.Objects.requireNonNull(filesystem.get(), "filesystem is not bound");
    }

    protected final void markExecuted() {
      executionCount.incrementAndGet();
    }

    final int executionCount() {
      return executionCount.get();
    }
  }

  private static final class ControlledRepositoryReadTool extends ControlledTool {

    private static final String NAME = "repository_read_target";

    private ControlledRepositoryReadTool(AtomicReference<AbstractFilesystem> filesystem) {
      super(NAME, "Read the fixed coding target with bounded output.", true, filesystem);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
      markExecuted();
      ReadResult read = filesystem().read(param.getRuntimeContext(), SANDBOX_SOURCE, 0, 80);
      String text = read.isSuccess() ? read.fileData().content() : "READ_FAILED: " + read.error();
      return Mono.just(result(param, text));
    }
  }

  private static final class ControlledApplyChangeTool extends ControlledTool {

    private static final String NAME = "repository_apply_fixture_change";

    private ControlledApplyChangeTool(AtomicReference<AbstractFilesystem> filesystem) {
      super(NAME, "Apply the one policy-approved fixture change.", false, filesystem);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
      markExecuted();
      AbstractSandboxFilesystem sandbox = (AbstractSandboxFilesystem) filesystem();
      ExecuteResponse response =
          sandbox.execute(
              param.getRuntimeContext(), "sed -i 's/before-m4/after-m4/' " + SANDBOX_SOURCE, 15);
      return Mono.just(
          result(
              param,
              response.isSuccess() ? "CHANGE_APPLIED" : "CHANGE_FAILED: " + response.output()));
    }
  }

  private static final class ControlledAcceptanceTool extends ControlledTool {

    private static final String NAME = "repository_run_acceptance";

    private ControlledAcceptanceTool(AtomicReference<AbstractFilesystem> filesystem) {
      super(NAME, "Compile the fixed target and verify the expected change.", false, filesystem);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
      markExecuted();
      AbstractSandboxFilesystem sandbox = (AbstractSandboxFilesystem) filesystem();
      ExecuteResponse response =
          sandbox.execute(
              param.getRuntimeContext(),
              "mkdir -p /workspace/repository/target/classes"
                  + " && javac -d /workspace/repository/target/classes "
                  + SANDBOX_SOURCE
                  + " && grep -q 'after-m4' "
                  + SANDBOX_SOURCE
                  + " && printf 'ACCEPTANCE_OK\\n'",
              30);
      return Mono.just(
          result(
              param,
              response.isSuccess()
                  ? response.output().strip()
                  : "ACCEPTANCE_FAILED: " + response.output()));
    }
  }

  private static final class TrackingExecutionGuard implements SandboxExecutionGuard {

    private final AtomicInteger enters = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final List<String> keys = new ArrayList<>();

    @Override
    public synchronized SandboxLease tryEnter(SandboxIsolationKey key) {
      enters.incrementAndGet();
      keys.add(key.getValue());
      return closes::incrementAndGet;
    }

    private int enterCount() {
      return enters.get();
    }

    private int closeCount() {
      return closes.get();
    }

    private synchronized List<String> keys() {
      return List.copyOf(keys);
    }
  }

  private record ProcessResult(int exitCode, String output) {

    private ProcessResult requireSuccess() {
      if (exitCode != 0) {
        throw new IllegalStateException(
            "Host command failed with exit code " + exitCode + ": " + output);
      }
      return this;
    }
  }
}
