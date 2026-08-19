package io.crewscope.infrastructure.workspace.repository;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment properties for CrewScope-owned AgentScope Docker Sandboxes. */
@ConfigurationProperties("crewscope.coding.sandbox")
public class TaskExecutionSandboxProperties {

    private String workspaceRoot = "/workspace";
    private String repositoryMount = "repository";
    private Duration dockerCommandTimeout = Duration.ofSeconds(30);
    private Duration pauseStopTimeout = Duration.ofSeconds(1);
    private TaskExecutionSandboxPauseMode pauseMode = TaskExecutionSandboxPauseMode.STOP;

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getRepositoryMount() {
        return repositoryMount;
    }

    public void setRepositoryMount(String repositoryMount) {
        this.repositoryMount = repositoryMount;
    }

    public Duration getDockerCommandTimeout() {
        return dockerCommandTimeout;
    }

    public void setDockerCommandTimeout(Duration dockerCommandTimeout) {
        this.dockerCommandTimeout = dockerCommandTimeout;
    }

    public Duration getPauseStopTimeout() {
        return pauseStopTimeout;
    }

    public void setPauseStopTimeout(Duration pauseStopTimeout) {
        this.pauseStopTimeout = pauseStopTimeout;
    }

    public TaskExecutionSandboxPauseMode getPauseMode() {
        return pauseMode;
    }

    public void setPauseMode(TaskExecutionSandboxPauseMode pauseMode) {
        this.pauseMode = pauseMode;
    }

    String requiredWorkspaceRoot() {
        if (workspaceRoot == null
                || !workspaceRoot.matches("/[a-zA-Z0-9._/-]+")
                || workspaceRoot.contains("//")
                || workspaceRoot.contains("/../")
                || workspaceRoot.endsWith("/..")) {
            throw new IllegalArgumentException(
                    "Sandbox workspace root must be a canonical absolute container path");
        }
        return workspaceRoot;
    }

    String requiredRepositoryMount() {
        if (repositoryMount == null || !repositoryMount.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "Sandbox repository mount must be one canonical relative segment");
        }
        return repositoryMount;
    }

    Duration requiredDockerCommandTimeout() {
        return positive(dockerCommandTimeout, "Docker command timeout");
    }

    Duration requiredPauseStopTimeout() {
        return positive(pauseStopTimeout, "Sandbox pause stop timeout");
    }

    TaskExecutionSandboxPauseMode requiredPauseMode() {
        if (pauseMode == null) {
            throw new IllegalArgumentException("Sandbox pause mode must be configured");
        }
        return pauseMode;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
