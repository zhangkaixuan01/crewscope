package io.crewscope.infrastructure.workspace.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Git coverage for the finite M5-I09 remote command templates. */
@Tag("integration")
class GitCommandExecutorM5I09IntegrationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void pushesExactShaWithLeaseAndMakesSameHeadRetryObservable() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("push"));
        GitCommandExecutor git = executor(Duration.ofSeconds(10), "git");
        GitAskPassEnvironment askPass = fixture.inertAskPass();
        RepositoryBranchReference branch = new RepositoryBranchReference("refs/heads/crewscope/task-1");

        git.fetchLocalCommit(fixture.mirror, fixture.source, fixture.delivery);
        assertEquals(Optional.empty(), git.findRemoteBranchHead(
                fixture.mirror, fixture.remote.toUri(), branch, askPass));
        git.pushBranch(
                fixture.mirror,
                fixture.remote.toUri(),
                branch,
                fixture.delivery,
                Optional.empty(),
                askPass);

        assertEquals(Optional.of(fixture.delivery), git.findRemoteBranchHead(
                fixture.mirror, fixture.remote.toUri(), branch, askPass));
        assertTrue(git.isAncestor(fixture.mirror, fixture.baseline, fixture.delivery));
        // Adapter returns ALREADY_PRESENT at this point and therefore never executes this template.
        assertEquals(fixture.delivery.value(), run(
                fixture.remote,
                "git",
                "rev-parse",
                branch.value()).trim());
    }

    @Test
    void forceWithLeaseRejectsRemoteHeadDriftWithoutOverwritingIt() throws Exception {
        requireGit();
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("lease"));
        GitCommandExecutor git = executor(Duration.ofSeconds(10), "git");
        GitAskPassEnvironment askPass = fixture.inertAskPass();
        RepositoryBranchReference branch = new RepositoryBranchReference("refs/heads/crewscope/task-2");
        run(fixture.source, "git", "branch", "crewscope/task-2", fixture.baseline.value());
        run(fixture.source, "git", "push", fixture.remote.toString(),
                fixture.baseline.value() + ":" + branch.value());
        git.fetchLocalCommit(fixture.mirror, fixture.source, fixture.delivery);

        GitCommandException conflict = assertThrows(
                GitCommandException.class,
                () -> git.pushBranch(
                        fixture.mirror,
                        fixture.remote.toUri(),
                        branch,
                        fixture.delivery,
                        Optional.empty(),
                        askPass));

        assertEquals(GitCommandError.CONFLICT, conflict.error());
        assertEquals(fixture.baseline.value(), run(
                fixture.remote, "git", "rev-parse", branch.value()).trim());
    }

    @Test
    void classifiesProtectedBranchStyleRejectionAndTimeoutWithoutRetainingOutput() throws Exception {
        Path rejected = executable("reject-git", """
                #!/bin/sh
                printf '%s\\n' 'remote: token-value protected branch hook declined'
                exit 1
                """);
        GitCommandExecutor rejecting = executor(Duration.ofSeconds(2), rejected.toString());
        GitCommandException protectedBranch = assertThrows(
                GitCommandException.class,
                () -> rejecting.pushBranch(
                        temporaryDirectory,
                        URI.create("https://github.com/crewscope/repository.git"),
                        new RepositoryBranchReference("refs/heads/crewscope/task-3"),
                        new RepositoryCommitId("a".repeat(40)),
                        Optional.empty(),
                        new GitAskPassEnvironment(rejected, rejected)));
        assertEquals(GitCommandError.REMOTE_REJECTED, protectedBranch.error());
        assertFalse((protectedBranch + " " + protectedBranch.getMessage()).contains("token-value"));

        Path sleeping = executable("sleep-git", """
                #!/bin/sh
                sleep 5
                """);
        GitCommandExecutor timingOut = executor(Duration.ofMillis(100), sleeping.toString());
        GitCommandException timeout = assertThrows(
                GitCommandException.class,
                () -> timingOut.isBareRepository(temporaryDirectory));
        assertEquals(GitCommandError.TIMEOUT, timeout.error());
    }

    private GitCommandExecutor executor(Duration timeout, String executable) {
        return new GitCommandExecutor(
                new GitCommandPolicy(
                        temporaryDirectory.resolve("home-" + System.nanoTime()),
                        timeout,
                        64 * 1024),
                executable);
    }

    private Path executable(String name, String script) throws IOException {
        Path path = Files.writeString(
                temporaryDirectory.resolve(name), script, StandardCharsets.UTF_8);
        assertTrue(path.toFile().setExecutable(true, true));
        return path;
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
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private record Fixture(
            Path source,
            Path remote,
            Path mirror,
            RepositoryCommitId baseline,
            RepositoryCommitId delivery,
            Path askPass,
            Path secret) {

        static Fixture create(Path root) throws Exception {
            Files.createDirectories(root);
            Path source = root.resolve("source");
            Path remote = root.resolve("remote.git");
            Path mirror = root.resolve("mirror.git");
            run(root, "git", "init", "--initial-branch=main", source.toString());
            run(source, "git", "config", "user.name", "M5-I09 Fixture");
            run(source, "git", "config", "user.email", "fixture@crewscope.local");
            Files.writeString(source.resolve("README.md"), "baseline\n");
            run(source, "git", "add", "README.md");
            run(source, "git", "commit", "-m", "baseline");
            RepositoryCommitId baseline = new RepositoryCommitId(
                    run(source, "git", "rev-parse", "HEAD").trim());
            run(root, "git", "init", "--bare", remote.toString());
            run(root, "git", "init", "--bare", mirror.toString());
            Files.writeString(source.resolve("README.md"), "delivery\n");
            run(source, "git", "commit", "-am", "delivery");
            RepositoryCommitId delivery = new RepositoryCommitId(
                    run(source, "git", "rev-parse", "HEAD").trim());
            Path askPass = Files.writeString(root.resolve("askpass"), "#!/bin/sh\nexit 1\n");
            assertTrue(askPass.toFile().setExecutable(true, true));
            Path secret = Files.writeString(root.resolve("secret"), "unused");
            return new Fixture(source, remote, mirror, baseline, delivery, askPass, secret);
        }

        GitAskPassEnvironment inertAskPass() {
            return new GitAskPassEnvironment(askPass, secret);
        }
    }
}
