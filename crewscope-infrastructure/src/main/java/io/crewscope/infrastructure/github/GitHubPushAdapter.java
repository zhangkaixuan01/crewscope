package io.crewscope.infrastructure.github;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubBranchQueryResult;
import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.application.github.GitHubPushPort;
import io.crewscope.application.github.GitHubPushResult;
import io.crewscope.application.github.GitHubRepositoryPreflightResult;
import io.crewscope.application.github.PushGitHubBranchRequest;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.ManagedDeliverySourceError;
import io.crewscope.infrastructure.workspace.repository.ManagedDeliverySourceException;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryDeliveryAccess;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Revalidates confirmed action authority and publishes one SHA RefSpec through a one-shot AskPass
 * credential window.
 */
public final class GitHubPushAdapter implements GitHubPushPort {

    private final GitHubProviderPort provider;
    private final GitHubActionAuthorityValidator authorityValidator;
    private final ManagedRepositoryDeliveryAccess sourceRepositoryAccess;
    private final ManagedGitHubMirrorResolver mirrorResolver;
    private final GitHubRemoteUriFactory remoteUriFactory;
    private final GitCommandExecutor gitCommands;
    private final GitHubPushProtocol pushProtocol;
    private final Path askPassRoot;
    private final GitHubConnectionGrantAuthorizer authorizer;
    private final TimeProvider timeProvider;

    public GitHubPushAdapter(
            GitHubProviderPort provider,
            ProviderBindingRepository providerBindingRepository,
            RepositoryBindingRepository repositoryBindingRepository,
            ManagedRepositoryResolver sourceRepositoryResolver,
            GitCommandExecutor gitCommands,
            Path mirrorRoot,
            Path askPassRoot,
            String requiredOwner,
            URI gitBaseUri,
            Duration credentialHandleTimeToLive,
            TimeProvider timeProvider,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository connectionGrantRepository,
            CredentialStore credentialStore) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.authorityValidator = new GitHubActionAuthorityValidator(
                providerBindingRepository, repositoryBindingRepository);
        this.gitCommands = Objects.requireNonNull(gitCommands, "gitCommands");
        this.pushProtocol = new GitHubPushProtocol(gitCommands);
        this.sourceRepositoryAccess = new ManagedRepositoryDeliveryAccess(
                Objects.requireNonNull(sourceRepositoryResolver, "sourceRepositoryResolver"),
                gitCommands);
        this.mirrorResolver = new ManagedGitHubMirrorResolver(
                mirrorRoot, requiredOwner, gitCommands);
        this.remoteUriFactory = new GitHubRemoteUriFactory(gitBaseUri);
        this.askPassRoot = Objects.requireNonNull(askPassRoot, "askPassRoot")
                .toAbsolutePath()
                .normalize();
        this.authorizer = new GitHubConnectionGrantAuthorizer(
                connectionRepository,
                connectionGrantRepository,
                credentialStore,
                timeProvider,
                credentialHandleTimeToLive);
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public GitHubPushResult pushBranch(PushGitHubBranchRequest request) {
        PushGitHubBranchRequest required = Objects.requireNonNull(request, "request");
        revalidateAuthority(required);
        GitHubRepositoryPreflightResult repository = provider.preflightRepository(
                required.repositoryPreflight());
        requirePreflightCoordinates(required, repository);
        revalidateAuthority(required);

        ManagedGitHubMirror mirror = mirrorResolver.resolveOrCreate(
                required.scope().organizationId(), required.action().repositoryId());
        verifyDeliveryLineage(mirror.path(), required);
        try {
            gitCommands.verifyCommit(mirror.path(), required.action().deliveryHead());
        } catch (RuntimeException failure) {
            throw failure(
                    GitHubPushErrorCode.MIRROR_UNAVAILABLE,
                    "Managed GitHub Mirror did not retain the delivery Head",
                    failure);
        }

        URI remote = remoteUriFactory.create(repository.fullName());
        revalidateAuthority(required);
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.repositoryPreflight().access(), "github:repository:push")) {
            return access.credentialHandle().useSecret(secret -> {
                try (GitAskPassSession askPass = GitAskPassSession.open(askPassRoot, secret)) {
                    return pushProtocol.push(
                            mirror.path(), remote, askPass.environment(), required.action());
                }
            });
        }
    }

    @Override
    public GitHubBranchQueryResult queryBranch(PushGitHubBranchRequest request) {
        PushGitHubBranchRequest required = Objects.requireNonNull(request, "request");
        revalidateAuthority(required);
        GitHubRepositoryPreflightResult repository = provider.preflightRepository(
                required.repositoryPreflight());
        requirePreflightCoordinates(required, repository);
        ManagedGitHubMirror mirror = mirrorResolver.resolveOrCreate(
                required.scope().organizationId(), required.action().repositoryId());
        URI remote = remoteUriFactory.create(repository.fullName());
        revalidateAuthority(required);
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.repositoryPreflight().access(), "github:repository:read")) {
            return access.credentialHandle().useSecret(secret -> {
                try (GitAskPassSession askPass = GitAskPassSession.open(askPassRoot, secret)) {
                    return new GitHubBranchQueryResult(
                            pushProtocol.query(
                                    mirror.path(), remote, askPass.environment(), required.action()),
                            timeProvider.now());
                }
            });
        }
    }

    private void revalidateAuthority(PushGitHubBranchRequest request) {
        authorityValidator.validatePush(
                request.scope(),
                request.providerAuthorization(),
                request.targetPrecondition());
    }

    private void verifyDeliveryLineage(Path mirror, PushGitHubBranchRequest request) {
        try {
            if (!sourceRepositoryAccess.verifyAndImport(
                    request.targetPrecondition().repositoryKey(),
                    request.targetPrecondition().baselineCommit(),
                    request.action().deliveryHead(),
                    mirror)) {
                throw failure(
                        GitHubPushErrorCode.DELIVERY_HEAD_MISMATCH,
                        "Confirmed delivery Head does not descend from its baseline");
            }
        } catch (GitHubPushException failure) {
            throw failure;
        } catch (ManagedDeliverySourceException failure) {
            throw failure(
                    switch (failure.error()) {
                        case BASELINE_UNAVAILABLE -> GitHubPushErrorCode.BASELINE_MISMATCH;
                        case DELIVERY_UNAVAILABLE -> GitHubPushErrorCode.DELIVERY_HEAD_MISMATCH;
                        case MIRROR_IMPORT_FAILED -> GitHubPushErrorCode.MIRROR_UNAVAILABLE;
                    },
                    "Confirmed Git delivery lineage is unavailable");
        }
    }

    private static void requirePreflightCoordinates(
            PushGitHubBranchRequest request, GitHubRepositoryPreflightResult repository) {
        if (!repository.connectionId().equals(request.providerAuthorization().connectionId())
                || repository.connectionVersion()
                        != request.providerAuthorization().connectionVersion()
                || !repository.grantId().equals(request.providerAuthorization().grantId())
                || repository.grantVersion() != request.providerAuthorization().grantVersion()
                || !repository.externalRepositoryId().equals(request.action().repositoryId().value())
                || !repository.defaultBranch()
                        .equals(request.targetPrecondition().defaultBranch())) {
            throw failure(
                    GitHubPushErrorCode.AUTHORITY_STALE,
                    "GitHub repository Preflight no longer matches the confirmed action");
        }
    }

    private static GitHubPushException failure(
            GitHubPushErrorCode code, String summary) {
        return new GitHubPushException(code, summary);
    }

    private static GitHubPushException failure(
            GitHubPushErrorCode code, String summary, Throwable cause) {
        return new GitHubPushException(code, summary, cause);
    }
}
