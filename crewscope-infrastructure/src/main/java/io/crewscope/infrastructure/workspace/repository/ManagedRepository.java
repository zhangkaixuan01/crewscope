package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.RepositoryKey;
import java.nio.file.Path;
import java.util.Objects;

/** Resolved managed repository whose canonical host path remains infrastructure-internal. */
public final class ManagedRepository {

    private final RepositoryKey repositoryKey;
    private final Path canonicalPath;

    ManagedRepository(RepositoryKey repositoryKey, Path canonicalPath) {
        this.repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        this.canonicalPath = Objects.requireNonNull(canonicalPath, "canonicalPath");
    }

    public RepositoryKey repositoryKey() {
        return repositoryKey;
    }

    Path canonicalPath() {
        return canonicalPath;
    }

    @Override
    public String toString() {
        return "ManagedRepository[repositoryKey=" + repositoryKey.value() + "]";
    }
}
