package io.crewscope.infrastructure.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.github.GitHubPushErrorCode;
import io.crewscope.application.github.GitHubPushException;
import io.crewscope.application.github.GitHubPushOutcome;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.infrastructure.workspace.git.GitAskPassEnvironment;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end M5-I09 Push protocol over real local bare remotes. */
@Tag("integration")
class GitHubPushProtocolM5I09IntegrationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void returnsSameHeadIdempotencyAndRejectsConflictAndNonFastForward() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("protocol"));
        GitCommandExecutor git = fixture.executor(Duration.ofSeconds(10));
        git.fetchLocalCommit(fixture.mirror, fixture.source, fixture.delivery);
        GitHubPushProtocol protocol = new GitHubPushProtocol(git);
        RepositoryBranchReference branch =
                new RepositoryBranchReference("refs/heads/crewscope/task-101");
        PushBranchActionParameters first = fixture.action(
                branch, fixture.delivery, Optional.empty());

        assertEquals(GitHubPushOutcome.PUSHED, protocol.push(
                fixture.mirror, fixture.remote.toUri(), fixture.askPass(), first).outcome());
        assertEquals(GitHubPushOutcome.ALREADY_PRESENT, protocol.push(
                fixture.mirror, fixture.remote.toUri(), fixture.askPass(), first).outcome());

        GitHubPushException conflict = assertThrows(
                GitHubPushException.class,
                () -> protocol.push(
                        fixture.mirror,
                        fixture.remote.toUri(),
                        fixture.askPass(),
                        fixture.action(branch, fixture.baseline, Optional.empty())));
        assertEquals(GitHubPushErrorCode.REMOTE_HEAD_CONFLICT, conflict.code());

        RepositoryCommitId divergent = fixture.createDivergentCommit();
        RepositoryBranchReference divergentBranch =
                new RepositoryBranchReference("refs/heads/crewscope/task-102");
        run(fixture.source, "git", "push", fixture.remote.toString(),
                divergent.value() + ":" + divergentBranch.value());
        GitHubPushException nonFastForward = assertThrows(
                GitHubPushException.class,
                () -> protocol.push(
                        fixture.mirror,
                        fixture.remote.toUri(),
                        fixture.askPass(),
                        fixture.action(
                                divergentBranch,
                                fixture.delivery,
                                Optional.of(divergent))));
        assertEquals(GitHubPushErrorCode.NON_FAST_FORWARD, nonFastForward.code());
        assertEquals(divergent.value(), run(
                fixture.remote, "git", "rev-parse", divergentBranch.value()).trim());
    }

    @Test
    void reconcilesACompletedPushWhoseClientProcessTimesOut() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("unknown"));
        Path actualGit = Path.of(run(temporaryDirectory, "/bin/sh", "-c", "command -v git").trim());
        Path wrapper = fixture.gitWrapper(actualGit);
        GitCommandExecutor git = new GitCommandExecutor(
                new GitCommandPolicy(
                        temporaryDirectory.resolve("timeout-home"),
                        Duration.ofMillis(1500),
                        64 * 1024),
                wrapper);
        fixture.executor(Duration.ofSeconds(10))
                .fetchLocalCommit(fixture.mirror, fixture.source, fixture.delivery);
        GitHubPushProtocol protocol = new GitHubPushProtocol(git);
        RepositoryBranchReference branch =
                new RepositoryBranchReference("refs/heads/crewscope/task-unknown");

        var result = protocol.push(
                fixture.mirror,
                fixture.remote.toUri(),
                fixture.askPass(),
                fixture.action(branch, fixture.delivery, Optional.empty()));

        assertEquals(GitHubPushOutcome.RECOVERED_AFTER_UNKNOWN, result.outcome());
        assertEquals(fixture.delivery.value(), run(
                fixture.remote, "git", "rev-parse", branch.value()).trim());
    }

    @Test
    void mapsProtectedBranchRejectionWithoutExposingRemoteOutput() throws Exception {
        Fixture fixture = Fixture.create(temporaryDirectory.resolve("protected"));
        Path actualGit = Path.of(run(temporaryDirectory, "/bin/sh", "-c", "command -v git").trim());
        GitCommandExecutor git = new GitCommandExecutor(
                new GitCommandPolicy(
                        temporaryDirectory.resolve("protected-home"),
                        Duration.ofSeconds(10),
                        64 * 1024),
                fixture.rejectingGitWrapper(actualGit));
        git.fetchLocalCommit(fixture.mirror, fixture.source, fixture.delivery);
        GitHubPushProtocol protocol = new GitHubPushProtocol(git);
        RepositoryBranchReference branch =
                new RepositoryBranchReference("refs/heads/crewscope/protected");

        GitHubPushException failure = assertThrows(
                GitHubPushException.class,
                () -> protocol.push(
                        fixture.mirror,
                        fixture.remote.toUri(),
                        fixture.askPass(),
                        fixture.action(branch, fixture.delivery, Optional.empty())));

        assertEquals(GitHubPushErrorCode.PROTECTED_BRANCH, failure.code());
        assertFalse((failure + " " + failure.getCause()).contains("protected-token"));
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
            Path root,
            Path source,
            Path remote,
            Path mirror,
            RepositoryCommitId baseline,
            RepositoryCommitId delivery,
            Path askPassProgram,
            Path secretFile) {

        static Fixture create(Path root) throws Exception {
            Process version = new ProcessBuilder("git", "--version").start();
            Assumptions.assumeTrue(
                    version.waitFor(5, TimeUnit.SECONDS) && version.exitValue() == 0);
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
            return new Fixture(root, source, remote, mirror, baseline, delivery, askPass, secret);
        }

        GitCommandExecutor executor(Duration timeout) {
            return new GitCommandExecutor(new GitCommandPolicy(
                    root.resolve("git-home-" + System.nanoTime()), timeout, 64 * 1024));
        }

        GitAskPassEnvironment askPass() {
            return new GitAskPassEnvironment(askPassProgram, secretFile);
        }

        PushBranchActionParameters action(
                RepositoryBranchReference branch,
                RepositoryCommitId head,
                Optional<RepositoryCommitId> expected) {
            return new PushBranchActionParameters(
                    new ExternalRepositoryId("4815"),
                    branch,
                    head,
                    expected,
                    new ConnectionId(UUID.fromString("00000000-0000-0000-0000-000000004815")));
        }

        RepositoryCommitId createDivergentCommit() throws Exception {
            run(source, "git", "checkout", "--detach", baseline.value());
            Files.writeString(source.resolve("README.md"), "divergent\n");
            run(source, "git", "commit", "-am", "divergent");
            RepositoryCommitId divergent = new RepositoryCommitId(
                    run(source, "git", "rev-parse", "HEAD").trim());
            run(source, "git", "checkout", "main");
            return divergent;
        }

        Path gitWrapper(Path actualGit) throws Exception {
            String quotedGit = "'" + actualGit.toString().replace("'", "'\\''") + "'";
            Path wrapper = Files.writeString(root.resolve("git-wrapper"), """
                    #!/bin/sh
                    is_push=false
                    for argument in "$@"; do
                      if [ "$argument" = "push" ]; then is_push=true; fi
                    done
                    if [ "$is_push" = "true" ]; then
                      %s "$@" || exit $?
                      sleep 5
                      exit 0
                    fi
                    exec %s "$@"
                    """.formatted(quotedGit, quotedGit));
            assertTrue(wrapper.toFile().setExecutable(true, true));
            return wrapper;
        }

        Path rejectingGitWrapper(Path actualGit) throws Exception {
            String quotedGit = "'" + actualGit.toString().replace("'", "'\\''") + "'";
            Path wrapper = Files.writeString(root.resolve("rejecting-git-wrapper"), """
                    #!/bin/sh
                    for argument in "$@"; do
                      if [ "$argument" = "push" ]; then
                        printf '%%s\\n' 'remote rejected: protected-token protected branch hook declined'
                        exit 1
                      fi
                    done
                    exec %s "$@"
                    """.formatted(quotedGit));
            assertTrue(wrapper.toFile().setExecutable(true, true));
            return wrapper;
        }
    }
}
