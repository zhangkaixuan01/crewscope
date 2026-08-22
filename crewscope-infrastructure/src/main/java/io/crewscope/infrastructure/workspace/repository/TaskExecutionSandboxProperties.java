package io.crewscope.infrastructure.workspace.repository;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment properties for CrewScope-owned AgentScope Docker Sandboxes. */
@ConfigurationProperties("crewscope.coding.sandbox")
public class TaskExecutionSandboxProperties {

    private String workspaceRoot = "/workspace";
    private String repositoryMount = "repository";
    private Duration dockerCommandTimeout = Duration.ofSeconds(30);
    private Duration pauseStopTimeout = Duration.ofSeconds(1);
    private TaskExecutionSandboxPauseMode pauseMode = TaskExecutionSandboxPauseMode.STOP;
    private String dependencyCacheRoot = "";
    private String dependencyCacheMount = "/maven-cache";

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

    public String getDependencyCacheRoot() {
        return dependencyCacheRoot;
    }

    public void setDependencyCacheRoot(String dependencyCacheRoot) {
        this.dependencyCacheRoot = dependencyCacheRoot;
    }

    public String getDependencyCacheMount() {
        return dependencyCacheMount;
    }

    public void setDependencyCacheMount(String dependencyCacheMount) {
        this.dependencyCacheMount = dependencyCacheMount;
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

    Optional<Path> dependencyCacheRootPath() {
        if (dependencyCacheRoot == null || dependencyCacheRoot.isBlank()) {
            return Optional.empty();
        }
        try {
            Path configured = Path.of(dependencyCacheRoot.strip());
            if (!configured.isAbsolute()
                    || Files.isSymbolicLink(configured)
                    || !Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Sandbox dependency cache root must be an absolute physical directory");
            }
            Path canonical = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    canonical, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.OWNER_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalArgumentException(
                        "Sandbox dependency cache root must be read-only");
            }
            return Optional.of(canonical);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Sandbox dependency cache root could not be verified", failure);
        }
    }

    String requiredDependencyCacheMount() {
        if (dependencyCacheMount == null
                || !dependencyCacheMount.matches("/[a-zA-Z0-9._/-]+")
                || dependencyCacheMount.contains("//")
                || dependencyCacheMount.contains("/../")
                || dependencyCacheMount.endsWith("/..")) {
            throw new IllegalArgumentException(
                    "Sandbox dependency cache mount must be a canonical absolute container path");
        }
        return dependencyCacheMount;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
