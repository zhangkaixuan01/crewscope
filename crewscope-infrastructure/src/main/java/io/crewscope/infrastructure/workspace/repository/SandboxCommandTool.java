package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.identity.Principal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Agent-callable facade with no raw command, argv, directory, environment or image parameter. */
public final class SandboxCommandTool {

    private final TaskExecutionSandboxCall call;
    private final String repositoryContainerPath;
    private final ExecutionWorkspace workspace;
    private final WorkspacePolicy policy;
    private final io.crewscope.domain.coding.BuildProfile profile;
    private final Principal actor;
    private final SandboxCommandUsage usage;
    private final BuildProfileCommandRunner runner;
    private final CommandEvidenceWriter evidenceWriter;
    private final Optional<TestEvidencePublisher> testEvidencePublisher;
    private final Optional<CodingWorkspaceExecution> codingExecution;
    private final int maximumToolResultBytes;

    SandboxCommandTool(
            TaskExecutionSandboxCall call,
            String repositoryContainerPath,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            io.crewscope.domain.coding.BuildProfile profile,
            Principal actor,
            SandboxCommandUsage usage,
            BuildProfileCommandRunner runner,
            CommandEvidenceWriter evidenceWriter,
            int maximumToolResultBytes) {
        this(
                call,
                repositoryContainerPath,
                workspace,
                policy,
                profile,
                actor,
                usage,
                runner,
                evidenceWriter,
                Optional.empty(),
                Optional.empty(),
                maximumToolResultBytes);
    }

    SandboxCommandTool(
            TaskExecutionSandboxCall call,
            String repositoryContainerPath,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            io.crewscope.domain.coding.BuildProfile profile,
            Principal actor,
            SandboxCommandUsage usage,
            BuildProfileCommandRunner runner,
            CommandEvidenceWriter evidenceWriter,
            TestEvidencePublisher testEvidencePublisher,
            CodingWorkspaceExecution codingExecution,
            int maximumToolResultBytes) {
        this(
                call,
                repositoryContainerPath,
                workspace,
                policy,
                profile,
                actor,
                usage,
                runner,
                evidenceWriter,
                Optional.of(Objects.requireNonNull(testEvidencePublisher, "testEvidencePublisher")),
                Optional.of(Objects.requireNonNull(codingExecution, "codingExecution")),
                maximumToolResultBytes);
    }

    private SandboxCommandTool(
            TaskExecutionSandboxCall call,
            String repositoryContainerPath,
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            io.crewscope.domain.coding.BuildProfile profile,
            Principal actor,
            SandboxCommandUsage usage,
            BuildProfileCommandRunner runner,
            CommandEvidenceWriter evidenceWriter,
            Optional<TestEvidencePublisher> testEvidencePublisher,
            Optional<CodingWorkspaceExecution> codingExecution,
            int maximumToolResultBytes) {
        this.call = Objects.requireNonNull(call, "call");
        this.repositoryContainerPath = Objects.requireNonNull(
                repositoryContainerPath, "repositoryContainerPath");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.evidenceWriter = Objects.requireNonNull(evidenceWriter, "evidenceWriter");
        this.testEvidencePublisher = Objects.requireNonNull(
                testEvidencePublisher, "testEvidencePublisher");
        this.codingExecution = Objects.requireNonNull(codingExecution, "codingExecution");
        if (this.testEvidencePublisher.isPresent() != this.codingExecution.isPresent()) {
            throw new IllegalArgumentException(
                    "TestEvidence publication requires the complete Coding execution context");
        }
        this.maximumToolResultBytes = maximumToolResultBytes;
    }

    @Tool(
            name = "coding_run_command",
            description = "Run one BuildProfile command kind with optional bounded module and test selectors.",
            strict = true,
            concurrencySafe = false)
    public String run(
            RuntimeContext runtimeContext,
            @ToolParam(
                            name = "command_kind",
                            description = "One of COMPILE, TEST, VERIFY, FORMAT_CHECK, ACCEPTANCE")
                    String commandKind,
            @ToolParam(
                            name = "modules",
                            description = "Optional exact module paths enabled by the BuildProfile",
                            required = false)
                    List<String> modules,
            @ToolParam(
                            name = "tests",
                            description = "Optional exact TestClass or TestClass#method selectors",
                            required = false)
                    List<String> tests,
            @ToolParam(
                            name = "timeout_seconds",
                            description = "Optional timeout inside the command and Sandbox limits",
                            required = false)
                    Integer timeoutSeconds) {
        Objects.requireNonNull(runtimeContext, "runtimeContext");
        requireCurrent();
        CommandKind kind = requireKind(commandKind);
        BuildProfileCommandRunner.PreparedCommand prepared = runner.prepare(
                repositoryContainerPath,
                policy,
                profile,
                kind,
                modules == null ? List.of() : modules,
                tests == null ? List.of() : tests,
                timeoutSeconds);
        SandboxCommandUsage.Reservation reservation = usage.reserve();
        SandboxCommandExecution execution = runner.run(call, runtimeContext, prepared);
        CommandEvidence evidence = evidenceWriter.write(
                workspace, policy, actor, reservation.sequence(), execution);
        Optional<io.crewscope.domain.coding.TestEvidence> testEvidence =
                testEvidencePublisher.flatMap(publisher -> publisher.publish(
                        codingExecution.orElseThrow(), actor, evidence, execution));
        return toolResult(evidence, testEvidence, execution, reservation);
    }

    private void requireCurrent() {
        try {
            call.requireCurrent();
        } catch (RuntimeException invalidContext) {
            throw new SandboxCommandException(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Command context or Lease/Fencing ownership is no longer current");
        }
    }

    private static CommandKind requireKind(String value) {
        if (value == null || value.isBlank()) {
            throw new SandboxCommandException(
                    SandboxCommandError.INVALID_REQUEST,
                    "Command kind must be supplied");
        }
        try {
            return CommandKind.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new SandboxCommandException(
                    SandboxCommandError.COMMAND_NOT_ALLOWED,
                    "Command kind is not enabled by the platform");
        }
    }

    private String toolResult(
            CommandEvidence evidence,
            Optional<io.crewscope.domain.coding.TestEvidence> testEvidence,
            SandboxCommandExecution execution,
            SandboxCommandUsage.Reservation reservation) {
        String header = "status=" + (evidence.succeeded() ? "succeeded" : "failed")
                + " commandKind=" + evidence.commandSpec().commandKind().name()
                + " termination=" + evidence.termination().name()
                + " exitCode=" + evidence.exitCode().map(String::valueOf).orElse("none")
                + " evidenceId=" + evidence.id()
                + " evidenceSequence=" + evidence.sequence()
                + " commandLogArtifactId=" + evidence.commandLog().artifactId()
                + testEvidence.map(value -> " testEvidenceId=" + value.id()
                                + " testEvidenceHash=" + value.evidenceHash()
                                + " testsSucceeded=" + value.succeeded())
                        .orElse("")
                + " commandCalls=" + reservation.commandCalls()
                + "/" + reservation.maximumCommandCalls()
                + " outputTruncated=" + execution.outputTruncated()
                + "\n--- stdout ---\n";
        String middle = "\n--- stderr ---\n";
        String output = sanitize(execution.stdout()) + middle + sanitize(execution.stderr());
        int available = Math.max(0, maximumToolResultBytes
                - header.getBytes(StandardCharsets.UTF_8).length);
        TruncatedText preview = truncateUtf8(output, available);
        return header + preview.value() + (preview.truncated() ? "\n[tool preview truncated]" : "");
    }

    private static String sanitize(String value) {
        return value.replace('\0', '\uFFFD');
    }

    private static TruncatedText truncateUtf8(String value, int maximumBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximumBytes) {
            return new TruncatedText(value, false);
        }
        int accepted = Math.min(maximumBytes, bytes.length);
        while (accepted > 0 && accepted < bytes.length && (bytes[accepted] & 0xC0) == 0x80) {
            accepted--;
        }
        return new TruncatedText(
                new String(bytes, 0, accepted, StandardCharsets.UTF_8), true);
    }

    private record TruncatedText(String value, boolean truncated) {}
}
