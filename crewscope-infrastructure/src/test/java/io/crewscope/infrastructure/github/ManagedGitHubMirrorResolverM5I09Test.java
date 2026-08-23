package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Managed Mirror identity and host-path trust boundary for M5-I09. */
class ManagedGitHubMirrorResolverM5I09Test {

    @TempDir Path temporaryDirectory;

    @Test
    void derivesAPlatformPathFromOrganizationProviderAndNumericRepositoryId() throws Exception {
        Path root = temporaryDirectory.resolve("mirrors");
        Files.createDirectory(root);
        String owner = Files.getOwner(root).getName();
        GitCommandExecutor git = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("git-home"), Duration.ofSeconds(10), 64 * 1024));
        ManagedGitHubMirrorResolver resolver = new ManagedGitHubMirrorResolver(root, owner, git);
        OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

        ManagedGitHubMirror first = resolver.resolveOrCreate(
                organizationId, new ExternalRepositoryId("4815"));
        ManagedGitHubMirror retry = resolver.resolveOrCreate(
                organizationId, new ExternalRepositoryId("4815"));

        assertEquals(first.path(), retry.path());
        assertTrue(first.path().startsWith(root.toRealPath()));
        assertTrue(first.path().endsWith(
                Path.of(organizationId.toString(), "github", "4815.git")));
        assertTrue(git.isBareRepository(first.path()));
    }

    @Test
    void rejectsNonNumericExternalIdentityBeforeResolvingAHostPath() throws Exception {
        Path root = temporaryDirectory.resolve("invalid-mirrors");
        Files.createDirectory(root);
        GitCommandExecutor git = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("invalid-home"), Duration.ofSeconds(10), 64 * 1024));
        ManagedGitHubMirrorResolver resolver = new ManagedGitHubMirrorResolver(
                root, Files.getOwner(root).getName(), git);

        GitHubPushException failure = assertThrows(
                GitHubPushException.class,
                () -> resolver.resolveOrCreate(
                        new OrganizationId(UUID.randomUUID()),
                        new ExternalRepositoryId("../model-input")));

        assertEquals(GitHubPushErrorCode.MIRROR_UNAVAILABLE, failure.code());
    }
}
