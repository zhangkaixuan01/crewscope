package io.crewscope.infrastructure.github;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.github.CreateGitHubDraftPullRequestRequest;
import io.crewscope.application.github.GitHubDraftPullRequestErrorCode;
import io.crewscope.application.github.GitHubDraftPullRequestException;
import io.crewscope.application.github.GitHubDraftPullRequestPort;
import io.crewscope.application.github.GitHubDraftPullRequestQueryResult;
import io.crewscope.application.github.GitHubDraftPullRequestResult;
import io.crewscope.application.github.GitHubProviderPort;
import io.crewscope.application.github.GitHubRepositoryPreflightResult;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.domain.shared.time.TimeProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Revalidates confirmed authority and executes one exact idempotent Draft PR protocol. */
public final class GitHubDraftPullRequestAdapter implements GitHubDraftPullRequestPort {

    private final GitHubProviderPort provider;
    private final GitHubActionAuthorityValidator authorityValidator;
    private final GitHubConnectionGrantAuthorizer authorizer;
    private final GitHubDraftPullRequestProtocol protocol;
    private final TimeProvider timeProvider;

    public GitHubDraftPullRequestAdapter(
            GitHubProviderPort provider,
            ProviderBindingRepository providerBindingRepository,
            RepositoryBindingRepository repositoryBindingRepository,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository connectionGrantRepository,
            CredentialStore credentialStore,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI apiBaseUri,
            URI webBaseUri,
            Duration requestTimeout,
            Duration credentialHandleTimeToLive,
            TimeProvider timeProvider,
            boolean allowLoopbackHttp) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.authorityValidator = new GitHubActionAuthorityValidator(
                providerBindingRepository, repositoryBindingRepository);
        this.authorizer = new GitHubConnectionGrantAuthorizer(
                connectionRepository,
                connectionGrantRepository,
                credentialStore,
                timeProvider,
                credentialHandleTimeToLive);
        this.protocol = new GitHubDraftPullRequestProtocol(
                httpClient,
                objectMapper,
                apiBaseUri,
                webBaseUri,
                requestTimeout,
                timeProvider,
                allowLoopbackHttp);
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public GitHubDraftPullRequestResult ensureDraft(
            CreateGitHubDraftPullRequestRequest request) {
        CreateGitHubDraftPullRequestRequest required = Objects.requireNonNull(request, "request");
        revalidate(required);
        GitHubRepositoryPreflightResult repository = provider.preflightRepository(
                required.repositoryPreflight());
        requirePreflightCoordinates(required, repository);
        revalidate(required);
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.repositoryPreflight().access(), "github:pull-request:create-draft")) {
            revalidate(required);
            return access.credentialHandle().useSecret(secret -> protocol.ensure(
                    required.action(), repository.fullName(), secret));
        }
    }

    @Override
    public GitHubDraftPullRequestQueryResult queryDraft(
            CreateGitHubDraftPullRequestRequest request) {
        CreateGitHubDraftPullRequestRequest required = Objects.requireNonNull(request, "request");
        revalidate(required);
        GitHubRepositoryPreflightResult repository = provider.preflightRepository(
                required.repositoryPreflight());
        requirePreflightCoordinates(required, repository);
        revalidate(required);
        try (AuthorizedGitHubAccess access = authorizer.authorize(
                required.repositoryPreflight().access(), "github:pull-request:read")) {
            return access.credentialHandle().useSecret(secret ->
                    new GitHubDraftPullRequestQueryResult(
                            protocol.query(required.action(), repository.fullName(), secret),
                            timeProvider.now()));
        }
    }

    private void revalidate(CreateGitHubDraftPullRequestRequest request) {
        authorityValidator.validateDraftPullRequest(
                request.scope(),
                request.providerAuthorization(),
                request.targetPrecondition());
    }

    private static void requirePreflightCoordinates(
            CreateGitHubDraftPullRequestRequest request,
            GitHubRepositoryPreflightResult repository) {
        if (!repository.connectionId().equals(request.providerAuthorization().connectionId())
                || repository.connectionVersion()
                        != request.providerAuthorization().connectionVersion()
                || !repository.grantId().equals(request.providerAuthorization().grantId())
                || repository.grantVersion() != request.providerAuthorization().grantVersion()
                || !repository.externalRepositoryId().equals(request.action().repositoryId().value())
                || !repository.defaultBranch().equals(request.action().base())) {
            throw new GitHubDraftPullRequestException(
                    GitHubDraftPullRequestErrorCode.AUTHORITY_STALE,
                    "GitHub repository Preflight no longer matches the confirmed action");
        }
    }
}
