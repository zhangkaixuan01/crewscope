package io.crewscope.infrastructure.workspace.repository;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment properties for the M4 managed repository trust boundary. */
@ConfigurationProperties("crewscope.coding.repository")
public class ManagedRepositoryProperties {

    private String managedRoot = "./var/crewscope/repositories";
    private String requiredOwner = System.getProperty("user.name");

    public String getManagedRoot() {
        return managedRoot;
    }

    public void setManagedRoot(String managedRoot) {
        this.managedRoot = managedRoot;
    }

    public String getRequiredOwner() {
        return requiredOwner;
    }

    public void setRequiredOwner(String requiredOwner) {
        this.requiredOwner = requiredOwner;
    }

    public Path managedRootPath() {
        if (managedRoot == null || managedRoot.isBlank()) {
            throw new IllegalArgumentException("Managed repository root must be non-blank");
        }
        try {
            return Path.of(managedRoot);
        } catch (InvalidPathException invalidPath) {
            throw new IllegalArgumentException("Managed repository root is invalid");
        }
    }
}
