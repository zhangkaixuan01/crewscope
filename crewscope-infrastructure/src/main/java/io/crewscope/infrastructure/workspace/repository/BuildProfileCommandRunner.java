package io.crewscope.infrastructure.workspace.repository;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandSelectorPolicy;
import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves one fixed BuildProfile slot into typed argv and executes it through AgentScope. */
final class BuildProfileCommandRunner {

    private final Clock clock;

    BuildProfileCommandRunner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    SandboxCommandExecution run(
            TaskExecutionSandboxCall call,
            RuntimeContext runtimeContext,
            String repositoryContainerPath,
            WorkspacePolicy policy,
            BuildProfile profile,
            CommandKind kind,
            List<String> modules,
            List<String> tests,
            Integer requestedTimeoutSeconds) {
        PreparedCommand prepared = prepare(
                repositoryContainerPath,
                policy,
                profile,
                kind,
                modules,
                tests,
                requestedTimeoutSeconds);
        return run(call, runtimeContext, prepared);
    }

    PreparedCommand prepare(
            String repositoryContainerPath,
            WorkspacePolicy policy,
            BuildProfile profile,
            CommandKind kind,
            List<String> modules,
            List<String> tests,
            Integer requestedTimeoutSeconds) {
        BuildCommand command = requireCommand(policy, profile, kind);
        List<String> safeModules = requireUnique(modules, "modules");
        List<String> safeTests = requireUnique(tests, "tests");
        requireSelectors(command.selectorPolicy(), safeModules, safeTests);
        List<String> argv = resolveArgv(profile.buildTool(), command, kind, safeModules, safeTests);
        int timeout = resolveTimeout(command, requestedTimeoutSeconds);
        CommandSpec spec = CommandSpec.capture(policy, profile, kind, argv, timeout);
        return new PreparedCommand(spec, shellCommand(repositoryContainerPath, spec));
    }

    SandboxCommandExecution run(
            TaskExecutionSandboxCall call,
            RuntimeContext runtimeContext,
            PreparedCommand preparedCommand) {
        TaskExecutionSandboxCall guardedCall = Objects.requireNonNull(call, "call");
        guardedCall.requireCurrent();
        Objects.requireNonNull(runtimeContext, "runtimeContext");
        PreparedCommand prepared = Objects.requireNonNull(preparedCommand, "preparedCommand");
        CommandSpec spec = prepared.commandSpec();
        Sandbox sandbox = guardedCall.sandboxContext().getExternalSandbox();
        UtcTimestamp startedAt = now();
        try {
            ExecResult result = sandbox.exec(
                    runtimeContext, prepared.shellCommand(), spec.timeoutSeconds());
            UtcTimestamp finishedAt = nowNotBefore(startedAt);
            if (result.truncated()) {
                return execution(
                        spec,
                        startedAt,
                        finishedAt,
                        CommandTermination.OUTPUT_LIMIT_EXCEEDED,
                        Optional.empty(),
                        result.stdout(),
                        result.stderr(),
                        true);
            }
            return execution(
                    spec,
                    startedAt,
                    finishedAt,
                    CommandTermination.EXITED,
                    Optional.of(result.exitCode()),
                    result.stdout(),
                    result.stderr(),
                    false);
        } catch (TaskExecutionSandboxOutputLimitException exceeded) {
            return execution(
                    spec,
                    startedAt,
                    nowNotBefore(startedAt),
                    CommandTermination.OUTPUT_LIMIT_EXCEEDED,
                    Optional.empty(),
                    exceeded.stdout(),
                    exceeded.stderr(),
                    true);
        } catch (SandboxException.ExecException exited) {
            return execution(
                    spec,
                    startedAt,
                    nowNotBefore(startedAt),
                    CommandTermination.EXITED,
                    Optional.of(exited.getExitCode()),
                    exited.getStdout(),
                    exited.getStderr(),
                    false);
        } catch (SandboxException.ExecTimeoutException timedOut) {
            CommandTermination termination = resetTimedOutCommand(guardedCall)
                    ? CommandTermination.TIMED_OUT
                    : CommandTermination.SANDBOX_POLICY_VIOLATION;
            return execution(
                    spec,
                    startedAt,
                    nowNotBefore(startedAt),
                    termination,
                    Optional.empty(),
                    "",
                    "",
                    false);
        } catch (TaskExecutionSandboxException policyViolation) {
            return execution(
                    spec,
                    startedAt,
                    nowNotBefore(startedAt),
                    CommandTermination.SANDBOX_POLICY_VIOLATION,
                    Optional.empty(),
                    "",
                    "",
                    false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            CommandTermination termination = resetTimedOutCommand(guardedCall)
                    ? CommandTermination.CANCELLED
                    : CommandTermination.SANDBOX_POLICY_VIOLATION;
            return execution(
                    spec,
                    startedAt,
                    nowNotBefore(startedAt),
                    termination,
                    Optional.empty(),
                    "",
                    "",
                    false);
        } catch (Exception startFailure) {
            return execution(
                    spec,
                    startedAt,
                    nowNotBefore(startedAt),
                    CommandTermination.START_FAILED,
                    Optional.empty(),
                    "",
                    "",
                    false);
        }
    }

    private static boolean resetTimedOutCommand(TaskExecutionSandboxCall call) {
        try {
            call.resetAfterCommandTimeout();
            return true;
        } catch (RuntimeException unsafeReset) {
            return false;
        }
    }

    private static BuildCommand requireCommand(
            WorkspacePolicy policy, BuildProfile profile, CommandKind kind) {
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        BuildProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        CommandKind requiredKind = Objects.requireNonNull(kind, "kind");
        if (!requiredPolicy.buildProfile().equals(requiredProfile.reference())) {
            throw failure(
                    SandboxCommandError.INVALID_CONTEXT,
                    "BuildProfile does not match the current Workspace Policy");
        }
        BuildCommand command = requiredPolicy.commandCatalog().commands().get(requiredKind);
        if (command == null
                || !command.equals(requiredProfile.commandCatalog().commands().get(requiredKind))) {
            throw failure(
                    SandboxCommandError.COMMAND_NOT_ALLOWED,
                    "Command kind is not enabled by the current Workspace Policy");
        }
        return command;
    }

    private static List<String> requireUnique(List<String> values, String name) {
        List<String> required = List.copyOf(Objects.requireNonNull(values, name));
        if (new HashSet<>(required).size() != required.size()) {
            throw failure(
                    SandboxCommandError.SELECTOR_NOT_ALLOWED,
                    "Command selectors must be unique");
        }
        return required;
    }

    private static void requireSelectors(
            CommandSelectorPolicy selectors, List<String> modules, List<String> tests) {
        if (!selectors.allowsModules(modules) || !selectors.allowsTests(tests)) {
            throw failure(
                    SandboxCommandError.SELECTOR_NOT_ALLOWED,
                    "Command selectors exceed the fixed BuildProfile policy");
        }
    }

    private static List<String> resolveArgv(
            BuildTool tool,
            BuildCommand command,
            CommandKind kind,
            List<String> modules,
            List<String> tests) {
        requireEncodingSafeModules(modules);
        List<String> argv = new ArrayList<>(command.argv());
        switch (tool) {
            case MAVEN, MAVEN_WRAPPER -> {
                if (!modules.isEmpty()) {
                    argv.add("-pl");
                    argv.add(String.join(",", modules));
                }
                if (!tests.isEmpty()) {
                    argv.add("-Dtest=" + String.join(",", tests));
                }
            }
            case GRADLE_WRAPPER -> {
                if (!modules.isEmpty()) {
                    if (command.argv().size() != 1) {
                        throw failure(
                                SandboxCommandError.INVALID_CONTEXT,
                                "Gradle module selection requires an executable-only command slot");
                    }
                    String task = gradleTask(kind);
                    modules.forEach(module -> {
                        if (".".equals(module)) {
                            throw failure(
                                    SandboxCommandError.SELECTOR_NOT_ALLOWED,
                                    "Gradle module selectors must name a subproject");
                        }
                        argv.add(":" + module.replace('/', ':') + ":" + task);
                    });
                }
                tests.forEach(test -> {
                    argv.add("--tests");
                    argv.add(test);
                });
            }
            case PROJECT_SCRIPT -> {
                if (!modules.isEmpty() || !tests.isEmpty()) {
                    throw failure(
                            SandboxCommandError.SELECTOR_NOT_ALLOWED,
                            "Project scripts do not define a dynamic selector protocol");
                }
            }
        }
        return List.copyOf(argv);
    }

    private static void requireEncodingSafeModules(List<String> modules) {
        for (String module : modules) {
            if (!".".equals(module)
                    && !module.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) {
                throw failure(
                        SandboxCommandError.SELECTOR_NOT_ALLOWED,
                        "Module selector cannot be encoded safely for the build tool");
            }
        }
    }

    private static String gradleTask(CommandKind kind) {
        return switch (kind) {
            case COMPILE -> "classes";
            case TEST, ACCEPTANCE -> "test";
            case VERIFY, FORMAT_CHECK -> "check";
        };
    }

    private static int resolveTimeout(BuildCommand command, Integer requested) {
        int timeout = requested == null ? command.defaultTimeoutSeconds() : requested;
        if (timeout < command.defaultTimeoutSeconds() || timeout > command.maxTimeoutSeconds()) {
            throw failure(
                    SandboxCommandError.INVALID_REQUEST,
                    "Command timeout is outside the fixed BuildProfile range");
        }
        return timeout;
    }

    private static String shellCommand(String repositoryContainerPath, CommandSpec spec) {
        String root = Objects.requireNonNull(repositoryContainerPath, "repositoryContainerPath");
        if (!root.matches("/[A-Za-z0-9._/-]+")) {
            throw failure(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Sandbox repository path is invalid");
        }
        String directory = ".".equals(spec.workingDirectory())
                ? root
                : root + "/" + spec.workingDirectory();
        StringBuilder encoded = new StringBuilder("cd ")
                .append(quote(directory))
                .append(" && exec ");
        spec.argv().forEach(argument -> encoded.append(quote(argument)).append(' '));
        return encoded.toString().stripTrailing();
    }

    /** POSIX single-quote encoding; argv remains data even though AgentScope 2.0.0 uses sh -c. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private UtcTimestamp now() {
        return UtcTimestamp.from(clock.instant());
    }

    private UtcTimestamp nowNotBefore(UtcTimestamp floor) {
        UtcTimestamp current = now();
        return current.compareTo(floor) < 0 ? floor : current;
    }

    private static SandboxCommandExecution execution(
            CommandSpec spec,
            UtcTimestamp startedAt,
            UtcTimestamp finishedAt,
            CommandTermination termination,
            Optional<Integer> exitCode,
            String stdout,
            String stderr,
            boolean outputTruncated) {
        return new SandboxCommandExecution(
                spec,
                startedAt,
                finishedAt,
                termination,
                exitCode,
                stdout,
                stderr,
                outputTruncated);
    }

    private static SandboxCommandException failure(SandboxCommandError error, String message) {
        return new SandboxCommandException(error, message);
    }

    /** Exact immutable execution material produced before consuming a Workspace command call. */
    record PreparedCommand(CommandSpec commandSpec, String shellCommand) {

        PreparedCommand {
            Objects.requireNonNull(commandSpec, "commandSpec");
            Objects.requireNonNull(shellCommand, "shellCommand");
        }
    }
}
