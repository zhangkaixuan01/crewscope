package io.crewscope.infrastructure.workspace.repository;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment properties for managed Worktree and lock roots. */
@ConfigurationProperties("crewscope.coding.worktree")
public class ManagedWorktreeProperties {

    private String root = "./var/crewscope/worktrees";
    private String lockRoot = "./var/crewscope/worktree-locks";
    private String requiredOwner = System.getProperty("user.name");

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getLockRoot() {
        return lockRoot;
    }

    public void setLockRoot(String lockRoot) {
        this.lockRoot = lockRoot;
    }

    public String getRequiredOwner() {
        return requiredOwner;
    }

    public void setRequiredOwner(String requiredOwner) {
        this.requiredOwner = requiredOwner;
    }

    Path rootPath() {
        return path(root, "Managed Worktree root");
    }

    Path lockRootPath() {
        return path(lockRoot, "Managed Worktree lock root");
    }

    private static Path path(String configured, String name) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        try {
            return Path.of(configured);
        } catch (InvalidPathException invalidPath) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
