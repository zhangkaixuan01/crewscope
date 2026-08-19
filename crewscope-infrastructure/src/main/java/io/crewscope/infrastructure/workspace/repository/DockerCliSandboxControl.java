package io.crewscope.infrastructure.workspace.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded Docker CLI adapter used only for inspect, pause and exact container removal. */
final class DockerCliSandboxControl implements DockerSandboxControl {

    private static final int MAXIMUM_OUTPUT_BYTES = 1024 * 1024;
    private static final long OUTPUT_DRAIN_GRACE_MILLIS = 5_000;

    private final ObjectMapper objectMapper;
    private final Duration commandTimeout;

    DockerCliSandboxControl(ObjectMapper objectMapper, Duration commandTimeout) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.commandTimeout = requirePositive(commandTimeout);
    }

    @Override
    public Optional<DockerContainerSnapshot> inspect(String exactContainerName) {
        DockerResult result = run(List.of("docker", "inspect", requiredName(exactContainerName)));
        if (result.exitCode() != 0) {
            String normalized = result.output().toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("no such object")
                    || normalized.contains("no such container")) {
                return Optional.empty();
            }
            throw failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "Docker daemon could not inspect the managed container");
        }
        try {
            JsonNode response = objectMapper.readTree(result.output());
            if (!response.isArray() || response.size() != 1 || !response.get(0).isObject()) {
                throw failure(
                        TaskExecutionSandboxError.CONTAINER_CORRUPT,
                        "Docker inspect returned an invalid managed container response");
            }
            return Optional.of(new DockerContainerSnapshot(response.get(0)));
        } catch (TaskExecutionSandboxException failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(
                    TaskExecutionSandboxError.CONTAINER_CORRUPT,
                    "Docker inspect returned an unreadable managed container response");
        }
    }

    @Override
    public List<DockerContainerSnapshot> listManaged(String organizationId, String environment) {
        String requiredOrganization = requiredOrganizationId(organizationId);
        String requiredEnvironment = requiredEnvironment(environment);
        DockerResult result = run(List.of(
                "docker",
                "ps",
                "--all",
                "--format",
                "{{.Names}}",
                "--filter",
                "label=io.crewscope.sandbox.managed=true",
                "--filter",
                "label=io.crewscope.sandbox.organization-id=" + requiredOrganization,
                "--filter",
                "label=io.crewscope.sandbox.environment=" + requiredEnvironment));
        if (result.exitCode() != 0) {
            throw failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "Docker daemon could not list managed containers");
        }
        List<DockerContainerSnapshot> containers = new java.util.ArrayList<>();
        result.output().lines()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .forEach(name -> inspect(requiredName(name)).ifPresent(containers::add));
        return List.copyOf(containers);
    }

    @Override
    public void stop(String exactContainerName, Duration gracefulTimeout) {
        long seconds = Math.max(1, requirePositive(gracefulTimeout).toSeconds());
        DockerResult result = run(List.of(
                "docker", "stop", "--timeout=" + seconds, requiredName(exactContainerName)));
        if (result.exitCode() != 0 && inspect(exactContainerName).map(DockerContainerSnapshot::running)
                .orElse(false)) {
            throw failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "Managed Sandbox container could not be stopped");
        }
    }

    @Override
    public void remove(String exactContainerName) {
        String name = requiredName(exactContainerName);
        DockerResult result = run(List.of("docker", "rm", "--force", name));
        if (result.exitCode() != 0 && inspect(name).isPresent()) {
            throw failure(
                    TaskExecutionSandboxError.CLEANUP_FAILED,
                    "Managed Sandbox container could not be removed");
        }
    }

    private DockerResult run(List<String> argv) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            DockerOutputCollector collector = new DockerOutputCollector(process);
            Thread drainer = new Thread(
                    collector,
                    "crewscope-docker-control-output");
            drainer.setDaemon(true);
            drainer.start();
            boolean exited = process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                terminateTree(process);
                closeQuietly(process.getInputStream());
                drainer.join(OUTPUT_DRAIN_GRACE_MILLIS);
                throw failure(
                        TaskExecutionSandboxError.COMMAND_FAILED,
                        "Docker control command exceeded its execution budget");
            }
            drainer.join(OUTPUT_DRAIN_GRACE_MILLIS);
            if (drainer.isAlive()) {
                terminateTree(process);
                closeQuietly(process.getInputStream());
                drainer.join(OUTPUT_DRAIN_GRACE_MILLIS);
                throw failure(
                        TaskExecutionSandboxError.COMMAND_FAILED,
                        "Docker control command output did not close");
            }
            if (collector.exceeded()) {
                throw failure(
                        TaskExecutionSandboxError.COMMAND_FAILED,
                        "Docker control command exceeded its output budget");
            }
            if (collector.failure().isPresent()) {
                throw failure(
                        TaskExecutionSandboxError.COMMAND_FAILED,
                        "Docker control command output could not be read");
            }
            return new DockerResult(process.exitValue(), collector.output());
        } catch (TaskExecutionSandboxException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateTree(process);
            }
            throw failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "Docker control command was interrupted");
        } catch (IOException failure) {
            throw failure(
                    TaskExecutionSandboxError.COMMAND_FAILED,
                    "Docker control command could not be started");
        }
    }

    private static void terminateTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroyForcibly);
        handle.destroyForcibly();
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The caller already reports the stable bounded-output failure.
        }
    }

    private static String requiredName(String value) {
        if (value == null || !value.matches("agentscope-sandbox-crewscope-[0-9a-f]{32}")) {
            throw failure(
                    TaskExecutionSandboxError.INVALID_CONFIGURATION,
                    "Managed Sandbox container name is invalid");
        }
        return value;
    }

    private static String requiredOrganizationId(String value) {
        try {
            String required = java.util.UUID.fromString(value).toString();
            if (!required.equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return required;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Managed Sandbox Organization ID is invalid");
        }
    }

    private static String requiredEnvironment(String value) {
        if (value == null || !value.matches("[a-z](?:[a-z0-9-]{0,62}[a-z0-9])?")) {
            throw new IllegalArgumentException("Managed Sandbox environment is invalid");
        }
        return value;
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("Docker command timeout must be positive");
        }
        return value;
    }

    private static TaskExecutionSandboxException failure(
            TaskExecutionSandboxError error, String summary) {
        return new TaskExecutionSandboxException(error, summary);
    }

    /** Drains the merged process stream completely while retaining only the configured prefix. */
    private static final class DockerOutputCollector implements Runnable {

        private final Process process;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicBoolean exceeded = new AtomicBoolean();
        private volatile IOException failure;

        private DockerOutputCollector(Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = MAXIMUM_OUTPUT_BYTES - output.size();
                    if (read > remaining) {
                        if (remaining > 0) {
                            output.write(buffer, 0, remaining);
                        }
                        exceeded.set(true);
                        terminateTree(process);
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
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private record DockerResult(int exitCode, String output) {}
}
