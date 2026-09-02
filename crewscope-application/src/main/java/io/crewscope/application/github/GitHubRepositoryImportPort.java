package io.crewscope.application.github;

/** Worker boundary for importing one authorized GitHub branch into the managed repository root. */
public interface GitHubRepositoryImportPort {
    GitHubRepositoryImportResult importRepository(GitHubRepositoryImportRequest request);
}
