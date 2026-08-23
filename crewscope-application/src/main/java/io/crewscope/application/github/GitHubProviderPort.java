package io.crewscope.application.github;

/** Read-side GitHub Provider boundary. Push and Draft PR remain separate Action Worker concerns. */
public interface GitHubProviderPort {

    GitHubConnectionProfile verifyConnection(VerifyGitHubConnectionRequest request);

    GitHubCatalogResult synchronizeCatalog(SyncGitHubCatalogRequest request);

    GitHubRepositoryPreflightResult preflightRepository(
            PreflightGitHubRepositoryRequest request);
}
