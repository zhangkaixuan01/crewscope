package io.crewscope.infrastructure.workspace.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Package-private process boundary that applies one policy to every typed Git operation. */
final class GitProcessRunner {

    private static final long TERMINATION_GRACE_MILLIS = 5_000;

    private final GitCommandPolicy policy;
    private final String executable;

    GitProcessRunner(GitCommandPolicy policy, String executable) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executable = requireExecutable(executable);
        initializeCommandHome(policy);
    }

    String run(List<String> arguments, Optional<String> standardInput) {
        return run(arguments, standardInput, Set.of(0));
    }

    /** Allows typed Git operations such as {@code diff --no-index} to declare success codes. */
    String run(
            List<String> arguments,
            Optional<String> standardInput,
            Set<Integer> successfulExitCodes) {
        Set<Integer> acceptedCodes = Set.copyOf(
                Objects.requireNonNull(successfulExitCodes, "successfulExitCodes"));
        if (acceptedCodes.isEmpty() || acceptedCodes.stream().anyMatch(code -> code < 0)) {
            throw new IllegalArgumentException("successfulExitCodes must not be empty or negative");
        }
        List<String> command = new ArrayList<>(arguments.size() + 3);
        command.add(executable);
        // Repository hooks are never trusted to execute in the host Worker process.
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.addAll(List.copyOf(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        configureEnvironment(builder.environment());

        Process process;
        try {
            process = builder.start();
        } catch (IOException startupFailure) {
            throw failure(
                    GitCommandError.COMMAND_FAILED,
                    "Git command could not be started",
                    OptionalInt.empty(),
                    startupFailure);
        }

        LimitedOutputCollector collector =
                new LimitedOutputCollector(process, policy.maximumOutputBytes());
        Thread collectorThread = new Thread(collector, "crewscope-git-output");
        collectorThread.setDaemon(true);
        collectorThread.start();

        try {
            writeStandardInput(process, standardInput);
            boolean completed = process.waitFor(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminateProcessTree(process);
                joinCollector(collectorThread);
                throw failure(
                        GitCommandError.TIMEOUT,
                        "Git command exceeded its time limit",
                        OptionalInt.empty());
            }
            if (!joinCollector(collectorThread)) {
                throw failure(
                        GitCommandError.COMMAND_FAILED,
                        "Git command output did not close",
                        OptionalInt.of(process.exitValue()));
            }
            if (collector.exceeded()) {
                throw failure(
                        GitCommandError.OUTPUT_LIMIT,
                        "Git command exceeded its output limit",
                        OptionalInt.of(process.exitValue()));
            }
            if (collector.failure().isPresent()) {
                throw failure(
                        GitCommandError.COMMAND_FAILED,
                        "Git command output could not be read",
                        OptionalInt.of(process.exitValue()),
                        collector.failure().orElseThrow());
            }
            String output = collector.output();
            int exitCode = process.exitValue();
            if (!acceptedCodes.contains(exitCode)) {
                GitCommandError classification = classify(output);
                throw failure(
                        classification,
                        safeSummary(classification),
                        OptionalInt.of(exitCode));
            }
            return output;
        } catch (InterruptedException interrupted) {
            terminateProcessTree(process);
            Thread.currentThread().interrupt();
            throw failure(
                    GitCommandError.COMMAND_FAILED,
                    "Git command execution was interrupted",
                    OptionalInt.empty(),
                    interrupted);
        } finally {
            if (process.isAlive()) {
                terminateProcessTree(process);
            }
        }
    }

    private void configureEnvironment(Map<String, String> environment) {
        String path = environment.get("PATH");
        environment.clear();
        if (path != null && !path.isBlank()) {
            environment.put("PATH", path);
        }
        environment.put("HOME", policy.commandHome().toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_PAGER", "cat");
        environment.put("PAGER", "cat");
        environment.put("LC_ALL", "C");
        environment.put("LANG", "C");
        environment.put("GIT_AUTHOR_NAME", "CrewScope Delivery");
        environment.put("GIT_AUTHOR_EMAIL", "delivery@crewscope.local");
        environment.put("GIT_COMMITTER_NAME", "CrewScope Delivery");
        environment.put("GIT_COMMITTER_EMAIL", "delivery@crewscope.local");
    }

    private static void writeStandardInput(Process process, Optional<String> standardInput) {
        try (OutputStream sink = process.getOutputStream()) {
            if (standardInput.isPresent()) {
                sink.write(standardInput.orElseThrow().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException inputFailure) {
            terminateProcessTree(process);
            throw failure(
                    GitCommandError.COMMAND_FAILED,
                    "Git command input could not be written",
                    OptionalInt.empty(),
                    inputFailure);
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean joinCollector(Thread collectorThread) throws InterruptedException {
        collectorThread.join(TERMINATION_GRACE_MILLIS);
        if (collectorThread.isAlive()) {
            collectorThread.interrupt();
            return false;
        }
        return true;
    }

    private static GitCommandError classify(String output) {
        String normalized = output.toLowerCase(Locale.ROOT);
        if (normalized.contains("not a git repository")) {
            return GitCommandError.NOT_A_REPOSITORY;
        }
        if (normalized.contains("already exists")
                || normalized.contains("already checked out")
                || normalized.contains("cannot lock ref")) {
            return GitCommandError.CONFLICT;
        }
        if (normalized.contains("unknown revision")
                || normalized.contains("needed a single revision")
                || normalized.contains("not a valid object")
                || normalized.contains("bad object")
                || normalized.contains("invalid object name")
                || normalized.contains("does not exist in")) {
            return GitCommandError.INVALID_REFERENCE;
        }
        return GitCommandError.COMMAND_FAILED;
    }

    private static String safeSummary(GitCommandError classification) {
        return switch (classification) {
            case NOT_A_REPOSITORY -> "Git repository validation failed";
            case INVALID_REFERENCE -> "Git reference validation failed";
            case CONFLICT -> "Git resource conflicts with an existing value";
            case TIMEOUT -> "Git command exceeded its time limit";
            case OUTPUT_LIMIT -> "Git command exceeded its output limit";
            case COMMAND_FAILED -> "Git command failed";
        };
    }

    private static String requireExecutable(String executable) {
        String value = Objects.requireNonNull(executable, "executable");
        if (value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Git executable must be non-blank");
        }
        return value;
    }

    private static void initializeCommandHome(GitCommandPolicy policy) {
        try {
            Files.createDirectories(policy.commandHome());
        } catch (IOException failure) {
            throw new IllegalStateException("Git command home could not be initialized", failure);
        }
    }

    private static GitCommandException failure(
            GitCommandError error, String summary, OptionalInt exitCode) {
        return new GitCommandException(error, summary, exitCode);
    }

    private static GitCommandException failure(
            GitCommandError error,
            String summary,
            OptionalInt exitCode,
            Throwable cause) {
        return new GitCommandException(error, summary, exitCode, cause);
    }

    private static final class LimitedOutputCollector implements Runnable {

        private final Process process;
        private final int maximumBytes;
        private final ByteArrayOutputStream output;
        private final AtomicBoolean exceeded;
        private volatile IOException failure;

        private LimitedOutputCollector(Process process, int maximumBytes) {
            this.process = process;
            this.maximumBytes = maximumBytes;
            this.output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1_024));
            this.exceeded = new AtomicBoolean();
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8 * 1_024];
            try (InputStream source = process.getInputStream()) {
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    int remaining = maximumBytes - output.size();
                    if (read > remaining) {
                        if (remaining > 0) {
                            output.write(buffer, 0, remaining);
                        }
                        exceeded.set(true);
                        terminateProcessTree(process);
                        return;
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException readFailure) {
                if (!exceeded.get()) {
                    failure = readFailure;
                }
            }
        }

        private boolean exceeded() {
            return exceeded.get();
        }

        private Optional<IOException> failure() {
            return Optional.ofNullable(failure);
        }

        private String output() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
