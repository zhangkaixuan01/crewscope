package io.crewscope.infrastructure.github;

import io.crewscope.application.github.GitHubRepositoryImportPort;
import io.crewscope.application.github.GitHubRepositoryImportRequest;
import io.crewscope.application.github.GitHubRepositoryImportResult;
import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.infrastructure.workspace.git.GitAskPassEnvironment;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import java.time.Duration;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Worker adapter that imports a verified remote branch into one owned bare repository. */
public final class GitHubRepositoryImportAdapter implements GitHubRepositoryImportPort {
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private final GitHubConnectionGrantAuthorizer authorizer;
    private final GitCommandExecutor git;
    private final Path managedRoot;
    private final Path askPassRoot;
    private final String requiredOwner;
    private final GitHubRemoteUriFactory remoteUris;

    public GitHubRepositoryImportAdapter(
            ConnectionRepository connections,
            ConnectionGrantRepository grants,
            CredentialStore credentials,
            GitCommandExecutor git,
            Path managedRoot,
            Path askPassRoot,
            String requiredOwner,
            URI gitBaseUri,
            TimeProvider timeProvider,
            Duration credentialHandleTtl) {
        this.authorizer = new GitHubConnectionGrantAuthorizer(
                connections, grants, credentials, Objects.requireNonNull(timeProvider, "timeProvider"),
                Objects.requireNonNull(credentialHandleTtl, "credentialHandleTtl"));
        this.git = Objects.requireNonNull(git, "git");
        this.managedRoot = initializeRoot(managedRoot);
        this.askPassRoot = Objects.requireNonNull(askPassRoot, "askPassRoot").toAbsolutePath().normalize();
        this.requiredOwner = Objects.requireNonNull(requiredOwner, "requiredOwner");
        this.remoteUris = new GitHubRemoteUriFactory(gitBaseUri);
        requireOwner(this.managedRoot);
    }

    @Override
    public GitHubRepositoryImportResult importRepository(GitHubRepositoryImportRequest request) {
        GitHubRepositoryImportRequest required = Objects.requireNonNull(request, "request");
        try (AuthorizedGitHubAccess access = authorizer.authorize(required.access(), "github:repository:import")) {
            return access.credentialHandle().useSecret(secret -> {
                Path target = managedRoot.resolve(required.repositoryKey().value() + ".git").normalize();
                if (!target.startsWith(managedRoot)) {
                    throw failure("Managed repository target is unavailable");
                }
                boolean existing = Files.exists(target, NO_FOLLOW);
                try {
                    if (existing) {
                        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, NO_FOLLOW)
                                || !git.isBareRepository(target)) {
                            throw failure("Managed repository target is unavailable");
                        }
                    } else {
                        git.initializeBareRepository(target);
                    }
                    requireOwner(target);
                    try (GitAskPassSession askPass = GitAskPassSession.open(askPassRoot, secret)) {
                        GitAskPassEnvironment environment = askPass.environment();
                        URI remote = remoteUris.create(required.repositoryFullName());
                        RepositoryBranchReference branch = new RepositoryBranchReference(
                                "refs/heads/" + required.defaultBranch().value());
                        RepositoryCommitId baseline = git.findRemoteBranchHead(
                                        target, remote, branch, environment)
                                .orElseThrow(() -> failure("GitHub default branch is unavailable"));
                        git.fetchRemoteBranch(target, remote, branch, environment);
                        git.updateBranchRef(target, required.defaultBranch(), baseline);
                        if (!git.isBareRepository(target)) {
                            throw failure("Managed repository is not bare");
                        }
                        return new GitHubRepositoryImportResult(required.repositoryKey(), baseline);
                    }
                } catch (RuntimeException failure) {
                    if (!existing) cleanup(target);
                    if (failure instanceof GitHubPushException safe) throw safe;
                    throw failure("GitHub repository import failed");
                }
            });
        }
    }

    private void requireOwner(Path path) {
        try {
            if (!requiredOwner.equals(Files.getOwner(path, NO_FOLLOW).getName())) {
                throw failure("Managed repository owner does not match the Worker owner");
            }
        } catch (IOException failure) {
            throw failure("Managed repository owner could not be verified");
        }
    }

    private static Path initializeRoot(Path configured) {
        Path root = Objects.requireNonNull(configured, "managedRoot").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("symbolic link");
            return root.toRealPath();
        } catch (IOException failure) {
            throw failure("Managed repository root is unavailable");
        }
    }

    private static void cleanup(Path target) {
        if (!Files.exists(target, NO_FOLLOW)) return;
        try (var paths = Files.walk(target)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static GitHubPushException failure(String message) {
        return new GitHubPushException(GitHubPushErrorCode.MIRROR_UNAVAILABLE, message);
    }
}
