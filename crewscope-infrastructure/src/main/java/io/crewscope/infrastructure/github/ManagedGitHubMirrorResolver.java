package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.infrastructure.workspace.git.GitCommandException;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** Maps Organization/Provider/GitHub Repository ID to one validated managed bare Mirror. */
final class ManagedGitHubMirrorResolver {

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final String PROVIDER_KEY = "github";

    private final Path root;
    private final String requiredOwner;
    private final GitCommandExecutor gitCommands;

    ManagedGitHubMirrorResolver(
            Path configuredRoot, String requiredOwner, GitCommandExecutor gitCommands) {
        this.root = initializeRoot(configuredRoot);
        this.requiredOwner = requireText(requiredOwner, "requiredOwner");
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        requireOwner(root);
    }

    synchronized ManagedGitHubMirror resolveOrCreate(
            OrganizationId organizationId, ExternalRepositoryId repositoryId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        ExternalRepositoryId repository = Objects.requireNonNull(repositoryId, "repositoryId");
        String externalId = numericRepositoryId(repository.value());
        Path providerDirectory = root.resolve(organization.toString()).resolve(PROVIDER_KEY);
        Path candidate = providerDirectory.resolve(externalId + ".git");
        try {
            createOwnedDirectory(providerDirectory.getParent());
            createOwnedDirectory(providerDirectory);
            if (!Files.exists(candidate, NO_FOLLOW)) {
                initializeAtomically(providerDirectory, candidate);
            }
            rejectLink(candidate);
            Path canonical = candidate.toRealPath();
            if (!canonical.startsWith(root) || !Files.isDirectory(canonical, NO_FOLLOW)) {
                throw failure("Managed GitHub Mirror escaped its configured root");
            }
            requireOwner(canonical);
            if (!gitCommands.isBareRepository(canonical)) {
                throw failure("Managed GitHub Mirror must be a bare repository");
            }
            return new ManagedGitHubMirror(organization, repository, canonical);
        } catch (GitHubPushException failure) {
            throw failure;
        } catch (IOException | GitCommandException failure) {
            throw failure("Managed GitHub Mirror is unavailable");
        }
    }

    private void initializeAtomically(Path parent, Path candidate) throws IOException {
        Path temporary = parent.resolve(".mirror-" + UUID.randomUUID() + ".git");
        try {
            gitCommands.initializeBareRepository(temporary);
            requireOwner(temporary);
            try {
                Files.move(temporary, candidate, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, candidate);
            } catch (FileAlreadyExistsException concurrentCreation) {
                deleteTemporary(temporary);
            }
        } catch (RuntimeException | IOException failure) {
            deleteTemporary(temporary);
            throw failure;
        }
    }

    private void createOwnedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, NO_FOLLOW)) {
            Files.createDirectory(directory);
        }
        rejectLink(directory);
        Path canonical = directory.toRealPath();
        if (!canonical.startsWith(root) || !Files.isDirectory(canonical, NO_FOLLOW)) {
            throw failure("Managed GitHub Mirror directory escaped its configured root");
        }
        requireOwner(canonical);
    }

    private void requireOwner(Path path) {
        try {
            if (!requiredOwner.equals(Files.getOwner(path, NO_FOLLOW).getName())) {
                throw failure("Managed GitHub Mirror owner does not match the Worker owner");
            }
        } catch (IOException failure) {
            throw failure("Managed GitHub Mirror owner could not be verified");
        }
    }

    private static void rejectLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw failure("Managed GitHub Mirror paths must not be symbolic links");
        }
    }

    private static Path initializeRoot(Path configuredRoot) {
        Path root = Objects.requireNonNull(configuredRoot, "configuredRoot")
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(root);
            rejectLink(root);
            return root.toRealPath();
        } catch (IOException failure) {
            throw failure("Managed GitHub Mirror root is unavailable");
        }
    }

    private static String numericRepositoryId(String value) {
        if (!value.matches("[1-9][0-9]{0,19}")) {
            throw failure("GitHub Repository ID is invalid");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.strip();
    }

    private static void deleteTemporary(Path temporary) {
        if (!Files.exists(temporary, NO_FOLLOW)) {
            return;
        }
        try (var paths = Files.walk(temporary)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The original safe failure remains authoritative.
                }
            });
        } catch (IOException ignored) {
            // The original safe failure remains authoritative.
        }
    }

    private static GitHubPushException failure(String summary) {
        return new GitHubPushException(GitHubPushErrorCode.MIRROR_UNAVAILABLE, summary);
    }

}
