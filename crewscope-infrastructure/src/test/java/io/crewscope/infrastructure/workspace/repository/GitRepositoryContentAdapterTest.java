package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.retrieval.RepositoryContentFailure;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileContent;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileRef;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Real-Git coverage for the repository content adapter (M10-I01b): listings and reads go
 * through the two fixed {@code ls-tree}/{@code cat-file} entries against a binding's
 * managed bare repository, reads are pinned to the exact commit, and every unrecoverable
 * failure surfaces as a stable failure code instead of Git detail.
 */
@Tag("integration")
class GitRepositoryContentAdapterTest {

    private static final String REPOSITORY_KEY = "demo-repo";

    @TempDir
    Path temporaryDirectory;

    @Test
    void listsEveryBlobWithItsExactSizeSortedByPath() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("listing"));

        List<RepositoryFileRef> files = fixture.adapter(64 * 1024).listFiles(
                fixture.organizationId, fixture.teamId, fixture.bindingId, fixture.firstCommit);

        assertEquals(
                List.of(
                        new RepositoryFileRef("README.md", utf8("hello\n").length),
                        new RepositoryFileRef("big.txt", utf8("x".repeat(4096)).length),
                        new RepositoryFileRef("docs/guide.md", utf8("guide\n").length),
                        new RepositoryFileRef("src/Main.java", utf8("class Main {}\n").length)),
                files);
    }

    @Test
    void readsTheExactContentPinnedToTheRequestedCommit() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("reading"));
        GitRepositoryContentAdapter adapter = fixture.adapter(64 * 1024);

        RepositoryFileContent first = adapter.readFile(
                fixture.organizationId, fixture.teamId, fixture.bindingId,
                fixture.firstCommit, "README.md");
        RepositoryFileContent second = adapter.readFile(
                fixture.organizationId, fixture.teamId, fixture.bindingId,
                fixture.secondCommit, "README.md");

        assertEquals("hello\n", first.content());
        assertEquals("second\n", second.content(), "a later commit never rewrites history");
    }

    @Test
    void reportsMissingPathsAsUnavailable() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("missing"));

        RepositoryContentFailure failure = assertThrows(
                RepositoryContentFailure.class,
                () -> fixture.adapter(64 * 1024).readFile(
                        fixture.organizationId, fixture.teamId, fixture.bindingId,
                        fixture.firstCommit, "missing.md"));

        assertEquals("REPOSITORY_UNAVAILABLE", failure.failureCode());
    }

    @Test
    void mapsOutputLimitReadsToRepositoryTooLarge() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("oversize"));
        // The command policy caps output far below the blob's size, standing in for the
        // production 16 MiB ceiling without writing a 16 MiB fixture.
        GitRepositoryContentAdapter adapter = fixture.adapter(2048);

        RepositoryContentFailure failure = assertThrows(
                RepositoryContentFailure.class,
                () -> adapter.readFile(fixture.organizationId, fixture.teamId, fixture.bindingId,
                        fixture.firstCommit, "big.txt"));

        assertEquals("REPOSITORY_TOO_LARGE", failure.failureCode());
    }

    @Test
    void unknownBindingsNeverReachGit() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("unknown"));

        RepositoryContentFailure failure = assertThrows(
                RepositoryContentFailure.class,
                () -> fixture.adapter(64 * 1024, true).listFiles(
                        fixture.organizationId, fixture.teamId, fixture.bindingId,
                        fixture.firstCommit));

        assertEquals("REPOSITORY_UNAVAILABLE", failure.failureCode());
        assertTrue(failure.getMessage().contains("binding"),
                "the summary names the stage that failed: " + failure.getMessage());
    }

    // ------------------------------------------------------------------ fixtures

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void requireGit() throws Exception {
        Process process = new ProcessBuilder("git", "--version").start();
        Assumptions.assumeTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0);
    }

    private static String run(Path workingDirectory, String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fixture command timed out");
        assertTrue(process.exitValue() == 0, output);
        return output;
    }

    /** One pushed bare repository plus the resolver over its managed root. */
    private record Fixture(
            OrganizationId organizationId,
            TeamId teamId,
            RepositoryBindingId bindingId,
            SourceCommit firstCommit,
            SourceCommit secondCommit,
            Path managedRoot,
            Path home) {

        static Fixture create(Path root) throws Exception {
            Files.createDirectories(root);
            Path source = root.resolve("source");
            Path managedRoot = root.resolve("managed");
            Path bare = managedRoot.resolve(REPOSITORY_KEY + ".git");
            Files.createDirectories(managedRoot);
            run(root, "git", "init", "--initial-branch=main", source.toString());
            run(source, "git", "config", "user.name", "I01b Fixture");
            run(source, "git", "config", "user.email", "fixture@crewscope.local");
            Files.writeString(source.resolve("README.md"), "hello\n");
            Files.createDirectories(source.resolve("docs"));
            Files.writeString(source.resolve("docs/guide.md"), "guide\n");
            Files.createDirectories(source.resolve("src"));
            Files.writeString(source.resolve("src/Main.java"), "class Main {}\n");
            Files.writeString(source.resolve("big.txt"), "x".repeat(4096));
            run(source, "git", "add", "--all");
            run(source, "git", "commit", "-m", "first");
            SourceCommit first = new SourceCommit(run(source, "git", "rev-parse", "HEAD").trim());
            Files.writeString(source.resolve("README.md"), "second\n");
            run(source, "git", "commit", "-am", "second");
            SourceCommit second = new SourceCommit(run(source, "git", "rev-parse", "HEAD").trim());
            run(root, "git", "init", "--bare", bare.toString());
            run(source, "git", "push", bare.toString(), "HEAD:refs/heads/main");
            return new Fixture(
                    OrganizationId.generate(),
                    TeamId.generate(),
                    RepositoryBindingId.generate(),
                    first,
                    second,
                    managedRoot,
                    root.resolve("home"));
        }

        GitRepositoryContentAdapter adapter(int maximumOutputBytes) {
            return adapter(maximumOutputBytes, false);
        }

        GitRepositoryContentAdapter adapter(int maximumOutputBytes, boolean unknownBinding) {
            GitCommandExecutor gitCommands = new GitCommandExecutor(
                    new GitCommandPolicy(home, Duration.ofSeconds(10), maximumOutputBytes));
            ManagedRepositoryResolver resolver = new ManagedRepositoryResolver(
                    managedRoot, ownerOfManagedRoot(), gitCommands);
            return new GitRepositoryContentAdapter(
                    fakeJdbc(unknownBinding), resolver, gitCommands);
        }

        private String ownerOfManagedRoot() {
            try {
                return Files.getOwner(managedRoot).getName();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("managed root owner unavailable", failure);
            }
        }

        /** Stands in for the binding lookup; the adapter only needs the key column. */
        private static NamedParameterJdbcTemplate fakeJdbc(boolean unknownBinding) {
            return new NamedParameterJdbcTemplate(new SimpleDriverDataSource()) {
                @Override
                public <T> T queryForObject(
                        String sql, SqlParameterSource parameters, Class<T> requiredType) {
                    if (unknownBinding) {
                        throw new EmptyResultDataAccessException(1);
                    }
                    return requiredType.cast(REPOSITORY_KEY);
                }
            };
        }
    }
}
