package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSelectorPolicy;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.coding.WorkspacePolicyReference;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.task.TaskFactHash;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Fixed argv, selector, timeout and failure classification coverage for the M4-I07 runner. */
class BuildProfileCommandRunnerM4I07Test {

    private static final Instant NOW = Instant.parse("2026-08-18T14:00:00Z");

    private final TaskExecutionSandboxCall call = mock(TaskExecutionSandboxCall.class);
    private final Sandbox sandbox = mock(Sandbox.class);
    private final RuntimeContext runtimeContext = RuntimeContext.builder()
            .userId("m4-i07-user")
            .sessionId("m4-i07-session")
            .build();
    private final BuildProfileCommandRunner runner = new BuildProfileCommandRunner(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(call.sandboxContext())
                .thenReturn(SandboxContext.builder().externalSandbox(sandbox).build());
    }

    @Test
    void resolvesMavenSelectorsAndQuotesEveryFixedArgumentAsData() throws Exception {
        String fixedData = "-Dlabel=one;touch owned";
        BuildProfile profile = profile(
                BuildTool.MAVEN,
                new BuildCommand(
                        "command.mavenTest",
                        List.of("mvn", "test", fixedData),
                        ".",
                        30,
                        120,
                        new CommandSelectorPolicy(List.of("module-a"), 1, 2, 256)));
        WorkspacePolicy policy = policy(profile);
        when(sandbox.exec(any(), anyString(), anyInt()))
                .thenReturn(new ExecResult(0, "ok\n", "", false));

        SandboxCommandExecution result = runner.run(
                call,
                runtimeContext,
                "/workspace/repository",
                policy,
                profile,
                CommandKind.TEST,
                List.of("module-a"),
                List.of("com.example.OneTest#works", "com.example.TwoTest"),
                45);

        assertEquals(
                List.of(
                        "mvn",
                        "test",
                        fixedData,
                        "-pl",
                        "module-a",
                        "-Dtest=com.example.OneTest#works,com.example.TwoTest",
                        "-Dsurefire.failIfNoSpecifiedTests=false"),
                result.commandSpec().argv());
        assertEquals(CommandTermination.EXITED, result.termination());
        assertEquals(0, result.exitCode().orElseThrow());
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(sandbox).exec(any(), command.capture(), org.mockito.ArgumentMatchers.eq(45));
        assertTrue(command.getValue().contains("'-Dlabel=one;touch owned'"));
        assertFalse(command.getValue().contains(";touch owned &&"));
    }

    @Test
    void registersExactlyOneStructuredCommandToolWithoutNativeShellSurface() {
        BuildProfile profile = profile(
                BuildTool.MAVEN_WRAPPER,
                command("./mvnw", List.of(), 0));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SandboxCommandTool(
                call,
                "/workspace/repository",
                mock(io.crewscope.domain.coding.ExecutionWorkspace.class),
                policy(profile),
                profile,
                mock(Principal.class),
                mock(SandboxCommandUsage.class),
                runner,
                mock(CommandEvidenceWriter.class),
                32_768));

        assertEquals(java.util.Set.of("coding_run_command"), toolkit.getToolNames());
        assertFalse(toolkit.getTool("coding_run_command").isReadOnly());
        assertFalse(toolkit.getToolNames().contains("execute"));
        assertFalse(toolkit.getToolNames().contains("shell_execute"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                toolkit.getTool("coding_run_command").getParameters().get("properties");
        assertEquals(
                java.util.Set.of("command_kind", "modules", "tests", "timeout_seconds"),
                properties.keySet());
        assertFalse(properties.containsKey("command"));
        assertFalse(properties.containsKey("argv"));
        assertFalse(properties.containsKey("working_directory"));
        assertFalse(properties.containsKey("environment"));
    }

    @Test
    void supportsMavenWrapperGradleWrapperAndFixedProjectScriptEntrypoints() throws Exception {
        when(sandbox.exec(any(), anyString(), anyInt()))
                .thenReturn(new ExecResult(0, "ok", "", false));

        BuildProfile wrapper = profile(
                BuildTool.MAVEN_WRAPPER,
                command("./mvnw", List.of("module-a"), 1));
        assertEquals(
                "./mvnw",
                runner.run(
                                call,
                                runtimeContext,
                                "/workspace/repository",
                                policy(wrapper),
                                wrapper,
                                CommandKind.TEST,
                                List.of(),
                                List.of(),
                                null)
                        .commandSpec()
                        .argv()
                        .get(0));

        BuildProfile gradle = profile(
                BuildTool.GRADLE_WRAPPER,
                new BuildCommand(
                        "command.gradleTest",
                        List.of("./gradlew"),
                        ".",
                        30,
                        120,
                        new CommandSelectorPolicy(List.of("service/api"), 1, 2, 256)));
        assertEquals(
                List.of(
                        "./gradlew",
                        ":service:api:test",
                        "--tests",
                        "com.example.ApiTest",
                        "--tests",
                        "com.example.OtherTest#works"),
                runner.run(
                                call,
                                runtimeContext,
                                "/workspace/repository",
                                policy(gradle),
                                gradle,
                                CommandKind.TEST,
                                List.of("service/api"),
                                List.of("com.example.ApiTest", "com.example.OtherTest#works"),
                                null)
                        .commandSpec()
                        .argv());

        BuildProfile script = profile(
                BuildTool.PROJECT_SCRIPT,
                new BuildCommand(
                        "command.acceptance",
                        List.of("./scripts/acceptance.sh", "--ci"),
                        ".",
                        30,
                        120));
        assertEquals(
                List.of("./scripts/acceptance.sh", "--ci"),
                runner.run(
                                call,
                                runtimeContext,
                                "/workspace/repository",
                                policy(script),
                                script,
                                CommandKind.TEST,
                                List.of(),
                                List.of(),
                                null)
                        .commandSpec()
                        .argv());
    }

    @Test
    void rejectsUnknownSelectorsDuplicateSelectorsAndProjectScriptOverlaysBeforeExec()
            throws Exception {
        BuildProfile profile = profile(
                BuildTool.MAVEN_WRAPPER,
                command("./mvnw", List.of("module-a"), 2));
        WorkspacePolicy policy = policy(profile);

        assertEquals(
                SandboxCommandError.SELECTOR_NOT_ALLOWED,
                assertThrows(
                                SandboxCommandException.class,
                                () -> runner.run(
                                        call,
                                        runtimeContext,
                                        "/workspace/repository",
                                        policy,
                                        profile,
                                        CommandKind.TEST,
                                        List.of("module-b"),
                                        List.of(),
                                        null))
                        .error());
        assertEquals(
                SandboxCommandError.SELECTOR_NOT_ALLOWED,
                assertThrows(
                                SandboxCommandException.class,
                                () -> runner.run(
                                        call,
                                        runtimeContext,
                                        "/workspace/repository",
                                        policy,
                                        profile,
                                        CommandKind.TEST,
                                        List.of(),
                                        List.of("com.example.Test", "com.example.Test"),
                                        null))
                        .error());
        BuildProfile encodingAttack = profile(
                BuildTool.MAVEN_WRAPPER,
                command("./mvnw", List.of("module-a,module-b"), 0));
        assertEquals(
                SandboxCommandError.SELECTOR_NOT_ALLOWED,
                assertThrows(
                                SandboxCommandException.class,
                                () -> runner.run(
                                        call,
                                        runtimeContext,
                                        "/workspace/repository",
                                        policy(encodingAttack),
                                        encodingAttack,
                                        CommandKind.TEST,
                                        List.of("module-a,module-b"),
                                        List.of(),
                                        null))
                        .error());
        verify(sandbox, never()).exec(any(), anyString(), anyInt());
    }

    @Test
    void recordsNonZeroTimeoutOutputLimitAndStartFailureWithoutLeakingExceptions() throws Exception {
        BuildProfile profile = profile(
                BuildTool.MAVEN_WRAPPER,
                command("./mvnw", List.of(), 0));
        WorkspacePolicy policy = policy(profile);

        when(sandbox.exec(any(), anyString(), anyInt()))
                .thenThrow(new SandboxException.ExecException(7, "compile output", "failed"));
        SandboxCommandExecution nonZero = run(profile, policy);
        assertEquals(CommandTermination.EXITED, nonZero.termination());
        assertEquals(7, nonZero.exitCode().orElseThrow());

        org.mockito.Mockito.reset(sandbox);
        when(sandbox.exec(any(), anyString(), anyInt()))
                .thenThrow(new SandboxException.ExecTimeoutException("sensitive raw command", 30));
        SandboxCommandExecution timeout = run(profile, policy);
        assertEquals(CommandTermination.TIMED_OUT, timeout.termination());
        verify(call).resetAfterCommandTimeout();

        org.mockito.Mockito.reset(sandbox);
        when(sandbox.exec(any(), anyString(), anyInt()))
                .thenThrow(new TaskExecutionSandboxOutputLimitException("bounded", "bounded-error"));
        SandboxCommandExecution outputLimit = run(profile, policy);
        assertEquals(CommandTermination.OUTPUT_LIMIT_EXCEEDED, outputLimit.termination());
        assertTrue(outputLimit.exitCode().isEmpty());

        org.mockito.Mockito.reset(sandbox);
        when(sandbox.exec(any(), anyString(), anyInt()))
                .thenThrow(new java.io.IOException("host path /private/tmp/secret"));
        SandboxCommandExecution startFailure = run(profile, policy);
        assertEquals(CommandTermination.START_FAILED, startFailure.termination());
        assertTrue(startFailure.stdout().isEmpty());
        assertTrue(startFailure.stderr().isEmpty());
    }

    private SandboxCommandExecution run(BuildProfile profile, WorkspacePolicy policy) {
        return runner.run(
                call,
                runtimeContext,
                "/workspace/repository",
                policy,
                profile,
                CommandKind.TEST,
                List.of(),
                List.of(),
                null);
    }

    private static BuildCommand command(
            String executable, List<String> modules, int maximumTests) {
        return new BuildCommand(
                "command.test",
                List.of(executable, "test"),
                ".",
                30,
                120,
                new CommandSelectorPolicy(modules, modules.isEmpty() ? 0 : 1, maximumTests, 256));
    }

    private static BuildProfile profile(BuildTool tool, BuildCommand command) {
        return BuildProfile.define(
                "m4-i07-profile",
                1,
                tool,
                17,
                new SandboxImageReference("maven@sha256:" + "a".repeat(64)),
                new CommandCatalog(Map.of(CommandKind.TEST, command)));
    }

    private static WorkspacePolicy policy(BuildProfile profile) {
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        when(policy.buildProfile()).thenReturn(profile.reference());
        when(policy.commandCatalog()).thenReturn(profile.commandCatalog());
        when(policy.reference()).thenReturn(new WorkspacePolicyReference(
                WorkspacePolicyId.generate(), TaskFactHash.sha256("m4-i07-policy")));
        when(policy.sandboxBudget()).thenReturn(new SandboxResourceBudget(
                SandboxNetworkMode.NONE, 1, 256, 32, 120, 65_536, true));
        return policy;
    }
}
