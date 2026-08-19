package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves a stable repository key beneath one canonical Worker-managed repository root. */
public final class ManagedRepositoryResolver {

    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private final Path managedRoot;
    private final String requiredOwner;
    private final GitCommandExecutor gitCommands;

    public ManagedRepositoryResolver(
            Path managedRoot, String requiredOwner, GitCommandExecutor gitCommands) {
        this.managedRoot = canonicalManagedRoot(managedRoot);
        this.requiredOwner = requireOwner(requiredOwner);
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        requireOwner(
                this.managedRoot,
                "Managed repository root owner does not match the Worker owner");
    }

    /** Resolves and validates one configured bare repository without exposing its host path. */
    public ManagedRepository resolve(RepositoryKey repositoryKey) {
        RepositoryKey key = Objects.requireNonNull(repositoryKey, "repositoryKey");
        Path candidate = managedRoot.resolve(key.value() + ".git").normalize();
        if (!candidate.startsWith(managedRoot)) {
            throw failure(
                    RepositoryPreflightError.PATH_ESCAPE,
                    "Repository path escaped its managed root");
        }
        rejectSymbolicLink(candidate);
        if (!Files.isDirectory(candidate, NO_FOLLOW_LINKS)) {
            throw failure(
                    RepositoryPreflightError.REPOSITORY_NOT_FOUND,
                    "Managed repository does not exist");
        }

        Path canonicalRepository;
        try {
            canonicalRepository = candidate.toRealPath();
        } catch (IOException failure) {
            throw failure(
                    RepositoryPreflightError.REPOSITORY_NOT_FOUND,
                    "Managed repository could not be resolved");
        }
        if (!canonicalRepository.startsWith(managedRoot)) {
            throw failure(
                    RepositoryPreflightError.PATH_ESCAPE,
                    "Repository path escaped its managed root");
        }
        requireOwner(
                canonicalRepository,
                "Managed repository owner does not match the Worker owner");
        requireBareRepository(canonicalRepository);
        return new ManagedRepository(key, canonicalRepository);
    }

    private void requireOwner(Path path, String mismatchSummary) {
        try {
            String actualOwner = Files.getOwner(path, NO_FOLLOW_LINKS).getName();
            if (!requiredOwner.equals(actualOwner)) {
                throw failure(
                        RepositoryPreflightError.OWNER_MISMATCH,
                        mismatchSummary);
            }
        } catch (IOException failure) {
            throw failure(
                    RepositoryPreflightError.OWNER_MISMATCH,
                    "Managed repository owner could not be verified");
        }
    }

    private void requireBareRepository(Path canonicalRepository) {
        try {
            if (!gitCommands.isBareRepository(canonicalRepository)) {
                throw failure(
                        RepositoryPreflightError.NOT_BARE_REPOSITORY,
                        "Managed repository must be bare");
            }
        } catch (GitCommandException failure) {
            throw failure(
                    RepositoryPreflightError.COMMAND_FAILED,
                    "Managed repository Git validation failed");
        }
    }

    private static Path canonicalManagedRoot(Path configuredRoot) {
        Path root = Objects.requireNonNull(configuredRoot, "managedRoot")
                .toAbsolutePath()
                .normalize();
        try {
            Path canonical = root.toRealPath();
            if (!Files.isDirectory(canonical, NO_FOLLOW_LINKS)) {
                throw failure(
                        RepositoryPreflightError.MANAGED_ROOT_INVALID,
                        "Managed repository root must be a directory");
            }
            return canonical;
        } catch (IOException failure) {
            throw failure(
                    RepositoryPreflightError.MANAGED_ROOT_INVALID,
                    "Managed repository root could not be resolved");
        }
    }

    private static void rejectSymbolicLink(Path candidate) {
        if (Files.isSymbolicLink(candidate)) {
            throw failure(
                    RepositoryPreflightError.SYMLINK_REJECTED,
                    "Managed repository path must not contain symbolic links");
        }
    }

    private static String requireOwner(String owner) {
        String value = Objects.requireNonNull(owner, "requiredOwner").trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Managed repository owner must be non-blank");
        }
        return value;
    }

    private static RepositoryPreflightException failure(
            RepositoryPreflightError error, String summary) {
        return new RepositoryPreflightException(error, summary);
    }
}
