package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.layout.BindMountEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M0-S03 evidence for Docker sandbox, bind-mounted worktree and host-side Git Diff. */
@Tag("docker")
@Tag("integration")
class HarnessAgentM0S03DockerIntegrationTest {

    private static final String IMAGE =
            "maven@sha256:29a1658b1f3078e07c2b17f7b519b45eb47f65d9628e887eac45a8c5c8f939d4";
    private static final String SOURCE_FILE =
            "src/main/java/io/crewscope/probe/Greeting.java";
    private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DIFF_TIMEOUT = Duration.ofSeconds(30);

    @TempDir Path hostWorkspace;

    @Test
    void dockerSandboxModifiesBindMountedGitRepositoryAndRunsMaven() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("docker", "info"), "Docker daemon is required");
        Assumptions.assumeTrue(
                commandSucceeds("docker", "image", "inspect", IMAGE),
                "Required image is missing; run: docker pull " + IMAGE);

        Path repository = initializeFixtureRepository();
        assertTrue(runHost(repository, "git", "status", "--porcelain").output().isBlank());

        ScriptedModel model =
                new ScriptedModel(
                        executeResponse(
                                "edit-source",
                                "sed -i 's/before-sandbox/after-sandbox/' " + SOURCE_FILE,
                                "repository",
                                30),
                        executeResponse(
                                "maven-validate",
                                "mvn --batch-mode --no-transfer-progress validate"
                                        + " && mkdir -p target"
                                        + " && printf 'maven-validate-ok\\n'"
                                        + " > target/m0-s03-maven.txt",
                                "repository",
                                60),
                        textResponse("sandbox-worktree-complete"));

        CompletableFuture<String> hostDiffWatcher =
                CompletableFuture.supplyAsync(() -> awaitGitDiff(repository, SOURCE_FILE));
        RuntimeContext runtimeContext =
                RuntimeContext.builder()
                        .userId("member-zhang")
                        .sessionId("m0-s03-docker-session")
                        .build();

        Msg result;
        try (HarnessAgent agent = newSandboxAgent(model, repository)) {
            // This isolated fixture is the explicit M0-S03 bypass boundary. Production shell
            // actions remain on the M0-S02 confirmation path.
            agent.setPermissionMode(runtimeContext, PermissionMode.BYPASS);
            result =
                    agent.call(
                                    "Modify the fixture and validate it with Maven",
                                    runtimeContext)
                            .block(AGENT_TIMEOUT);
        } catch (RuntimeException exception) {
            hostDiffWatcher.cancel(true);
            throw exception;
        }

        assertNotNull(result);
        assertEquals("sandbox-worktree-complete", result.getTextContent());
        assertEquals(3, model.callCount());
        String editRoundInput = allToolResults(model.request(1));
        String mavenRoundInput = allToolResults(model.request(2));
        assertTrue(editRoundInput.contains("Exit code: 0"), editRoundInput);
        assertTrue(mavenRoundInput.contains("Exit code: 0"), mavenRoundInput);

        String observedDiff = hostDiffWatcher.get(DIFF_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        String source = Files.readString(repository.resolve(SOURCE_FILE));
        String mavenMarker =
                Files.readString(repository.resolve("target/m0-s03-maven.txt")).strip();

        assertTrue(source.contains("after-sandbox"));
        assertEquals("maven-validate-ok", mavenMarker);
        assertTrue(observedDiff.contains("-        return \"before-sandbox\";"));
        assertTrue(observedDiff.contains("+        return \"after-sandbox\";"));
        assertEquals(
                SOURCE_FILE,
                runHost(repository, "git", "diff", "--name-only").requireSuccess().output().strip());
    }

    private HarnessAgent newSandboxAgent(ScriptedModel model, Path repository) throws IOException {
        BindMountEntry worktreeMount = new BindMountEntry();
        worktreeMount.setHostPath(repository.toRealPath().toString());
        worktreeMount.setReadOnly(false);

        WorkspaceSpec workspaceSpec = new WorkspaceSpec();
        workspaceSpec.setRoot("/workspace");
        workspaceSpec.setEntries(Map.of("repository", worktreeMount));

        DockerFilesystemSpec filesystem =
                new DockerFilesystemSpec()
                        .image(IMAGE)
                        .workspaceRoot("/workspace")
                        .network("none")
                        .workspaceSpec(workspaceSpec);
        filesystem.isolationScope(IsolationScope.SESSION);

        return HarnessAgent.builder()
                .name("crewscope-m0-s03-docker-agent")
                .sysPrompt("Execute the requested deterministic coding steps in the sandbox.")
                .model(model)
                .workspace(hostWorkspace)
                .filesystem(filesystem)
                .stateStore(new InMemoryAgentStateStore())
                .disableFilesystemTools()
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
                    <artifactId>m0-s03-fixture</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                repository.resolve(".gitignore"), "target/\n", StandardCharsets.UTF_8);
        Files.writeString(
                source,
                """
                package io.crewscope.probe;

                public final class Greeting {

                    private Greeting() {}

                    public static String message() {
                        return "before-sandbox";
                    }
                }
                """,
                StandardCharsets.UTF_8);

        runHost(repository, "git", "init", "--initial-branch=main").requireSuccess();
        runHost(repository, "git", "config", "user.name", "CrewScope M0 Probe")
                .requireSuccess();
        runHost(repository, "git", "config", "user.email", "m0-probe@crewscope.local")
                .requireSuccess();
        runHost(repository, "git", "add", ".").requireSuccess();
        runHost(repository, "git", "commit", "-m", "initial fixture").requireSuccess();
        return repository;
    }

    private static String awaitGitDiff(Path repository, String sourceFile) {
        long deadline = System.nanoTime() + DIFF_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                String diff =
                        runHost(
                                        repository,
                                        "git",
                                        "diff",
                                        "--no-ext-diff",
                                        "--unified=0",
                                        "--",
                                        sourceFile)
                                .requireSuccess()
                                .output();
                if (!diff.isBlank()) {
                    return diff;
                }
                Thread.sleep(50);
            } catch (IOException exception) {
                throw new IllegalStateException("Host Diff Watcher could not run Git", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Host Diff Watcher was interrupted", exception);
            }
        }
        throw new IllegalStateException("Host Diff Watcher did not observe a change");
    }

    private static ChatResponse executeResponse(
            String callId, String command, String workingDirectory, int timeoutSeconds) {
        Map<String, Object> input =
                Map.of(
                        "command", command,
                        "working_directory", workingDirectory,
                        "timeout", timeoutSeconds);
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(callId)
                                        .name("execute")
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

    private static String allToolResults(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(result -> result.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", (left, right) -> left + right);
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
