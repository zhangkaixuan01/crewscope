package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Git and filesystem attack tests for the M4-I02 managed repository trust boundary. */
class ManagedRepositoryResolverM4I02IntegrationTest {

    private static final Duration HOST_COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final RepositoryKey REPOSITORY_KEY = new RepositoryKey("repository-01");
    private static final RepositoryBranchName MAIN = new RepositoryBranchName("main");

    @TempDir Path temporaryDirectory;

    private Path managedRoot;
    private Path sourceRepository;
    private Path bareRepository;
    private GitCommandExecutor gitCommands;
    private ManagedRepositoryResolver resolver;
    private BaselinePreflight preflight;
    private RepositoryCommitId firstCommit;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
        managedRoot = Files.createDirectory(temporaryDirectory.resolve("managed"));
        sourceRepository = temporaryDirectory.resolve("source");
        bareRepository = managedRoot.resolve(REPOSITORY_KEY.value() + ".git");
        gitCommands = new GitCommandExecutor(new GitCommandPolicy(
                temporaryDirectory.resolve("command-home"), Duration.ofSeconds(10), 1024 * 1024));

        runRequired(temporaryDirectory, "git", "init", "--initial-branch=main", sourceRepository.toString());
        runRequired(sourceRepository, "git", "config", "user.name", "Fixture");
        runRequired(sourceRepository, "git", "config", "user.email", "fixture@crewscope.local");
        Files.writeString(sourceRepository.resolve("README.md"), "first\n", StandardCharsets.UTF_8);
        runRequired(sourceRepository, "git", "add", "README.md");
        runRequired(sourceRepository, "git", "commit", "-m", "first fixture");
        runRequired(
                temporaryDirectory,
                "git",
                "clone",
                "--bare",
                sourceRepository.toString(),
                bareRepository.toString());
        firstCommit = new RepositoryCommitId(runRequired(
                        sourceRepository, "git", "rev-parse", "HEAD")
                .trim());
        resolver = resolverForOwner(Files.getOwner(bareRepository).getName());
        preflight = new BaselinePreflight(resolver, gitCommands);
    }

    @Test
    void resolvesCanonicalManagedBareRepositoryAndCapturesFullCommit() throws Exception {
        ManagedRepository repository = resolver.resolve(REPOSITORY_KEY);
        BaselinePreflightResult result = preflight.capture(activeBinding(), MAIN);

        assertEquals(REPOSITORY_KEY, repository.repositoryKey());
        assertEquals(bareRepository.toRealPath(), repository.canonicalPath());
        assertEquals(firstCommit, result.baselineCommit());
        assertEquals(MAIN, result.baselineRef());
        assertFalse(repository.toString().contains(bareRepository.toString()));
        assertTrue(Arrays.stream(ManagedRepository.class.getMethods())
                .filter(method -> method.getDeclaringClass() == ManagedRepository.class)
                .noneMatch(method -> method.getReturnType() == Path.class
                        || Arrays.asList(method.getParameterTypes()).contains(Path.class)));
    }

    @Test
    void rejectsTraversalAndOptionShapedKeysBeforeFilesystemResolution() {
        assertThrows(DomainValidationException.class, () -> new RepositoryKey("../outside"));
        assertThrows(DomainValidationException.class, () -> new RepositoryKey("--help"));
    }

    @Test
    void rejectsRepositorySymbolicLinkWithoutFollowingItsTarget() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.git");
        Files.move(bareRepository, outside);
        Files.createSymbolicLink(bareRepository, outside);

        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class, () -> resolver.resolve(REPOSITORY_KEY));

        assertEquals(RepositoryPreflightError.SYMLINK_REJECTED, failure.error());
        assertFalse(failure.getMessage().contains(outside.toString()));
    }

    @Test
    void rejectsMissingRepositoryAndHidesManagedRoot() throws Exception {
        Files.move(bareRepository, temporaryDirectory.resolve("moved.git"));

        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class, () -> resolver.resolve(REPOSITORY_KEY));

        assertEquals(RepositoryPreflightError.REPOSITORY_NOT_FOUND, failure.error());
        assertFalse(failure.getMessage().contains(managedRoot.toString()));
        assertNull(failure.getCause());
    }

    @Test
    void rejectsRepositoryOwnedByAnUnexpectedWorkerIdentity() {
        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class,
                () -> resolverForOwner("not-the-fixture-owner").resolve(REPOSITORY_KEY));

        assertEquals(RepositoryPreflightError.OWNER_MISMATCH, failure.error());
    }

    @Test
    void rejectsCleanAndDirtyWorkingRepositoriesAsNonBareSources() throws Exception {
        Files.move(bareRepository, temporaryDirectory.resolve("original-bare.git"));
        copyWorkingRepositoryToManagedCandidate();

        RepositoryPreflightException cleanFailure = assertThrows(
                RepositoryPreflightException.class, () -> resolver.resolve(REPOSITORY_KEY));
        assertEquals(RepositoryPreflightError.NOT_BARE_REPOSITORY, cleanFailure.error());

        Files.writeString(
                bareRepository.resolve("README.md"), "dirty\n", StandardCharsets.UTF_8);
        RepositoryPreflightException dirtyFailure = assertThrows(
                RepositoryPreflightException.class, () -> resolver.resolve(REPOSITORY_KEY));
        assertEquals(RepositoryPreflightError.NOT_BARE_REPOSITORY, dirtyFailure.error());
    }

    @Test
    void rejectsDisabledBindingBeforeRepositoryOrRefResolution() {
        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class,
                () -> preflight.capture(disabledBinding(), new RepositoryBranchName("missing")));

        assertEquals(RepositoryPreflightError.BINDING_INACTIVE, failure.error());
    }

    @Test
    void rejectsMissingBaselineReferenceWithStableClassification() {
        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class,
                () -> preflight.capture(activeBinding(), new RepositoryBranchName("missing")));

        assertEquals(RepositoryPreflightError.REFERENCE_INVALID, failure.error());
        assertFalse(failure.getMessage().contains("missing"));
    }

    @Test
    void rejectsReferenceThatMovesBetweenCaptureAndPublication() throws Exception {
        BaselinePreflightResult captured = preflight.capture(activeBinding(), MAIN);
        RepositoryCommitId secondCommit = createSecondCommitAndMoveBareMain();

        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class,
                () -> preflight.verifyExpected(activeBinding(), MAIN, captured.baselineCommit()));

        assertEquals(RepositoryPreflightError.BASELINE_MOVED, failure.error());
        assertFalse(secondCommit.equals(captured.baselineCommit()));
    }

    @Test
    void historicalSnapshotIgnoresMovedRefWhenItsFixedCommitStillExists() throws Exception {
        createSecondCommitAndMoveBareMain();
        CodingTargetSnapshot snapshot = snapshot(firstCommit);

        BaselinePreflightResult result = preflight.verifySnapshot(snapshot);

        assertEquals(firstCommit, result.baselineCommit());
    }

    @Test
    void historicalSnapshotRejectsACommitThatDoesNotExist() {
        RepositoryCommitId missing =
                new RepositoryCommitId("ffffffffffffffffffffffffffffffffffffffff");

        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class,
                () -> preflight.verifySnapshot(snapshot(missing)));

        assertEquals(RepositoryPreflightError.COMMIT_NOT_FOUND, failure.error());
    }

    @Test
    void invalidManagedRootFailsClosedWithoutDisclosingItsPath() {
        Path missingRoot = temporaryDirectory.resolve("secret-missing-root");

        RepositoryPreflightException failure = assertThrows(
                RepositoryPreflightException.class,
                () -> new ManagedRepositoryResolver(
                        missingRoot, Files.getOwner(temporaryDirectory).getName(), gitCommands));

        assertEquals(RepositoryPreflightError.MANAGED_ROOT_INVALID, failure.error());
        assertFalse(failure.getMessage().contains(missingRoot.toString()));
    }

    private ManagedRepositoryResolver resolverForOwner(String owner) {
        return new ManagedRepositoryResolver(managedRoot, owner, gitCommands);
    }

    private RepositoryBinding activeBinding() {
        return binding(RepositoryBindingStatus.ACTIVE);
    }

    private RepositoryBinding disabledBinding() {
        return binding(RepositoryBindingStatus.DISABLED);
    }

    private RepositoryBinding binding(RepositoryBindingStatus status) {
        RepositoryBindingScope scope = new RepositoryBindingScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        PrincipalId actor = PrincipalId.generate();
        return RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                scope,
                RepositoryKind.LOCAL_MANAGED,
                REPOSITORY_KEY,
                MAIN,
                status,
                0,
                AuditMetadata.createdBy(
                        actor, UtcTimestamp.from(Instant.parse("2026-08-18T00:00:00Z"))));
    }

    private CodingTargetSnapshot snapshot(RepositoryCommitId commit) {
        CodingTargetSnapshot snapshot = mock(CodingTargetSnapshot.class);
        when(snapshot.repositoryKind()).thenReturn(RepositoryKind.LOCAL_MANAGED);
        when(snapshot.repositoryKey()).thenReturn(REPOSITORY_KEY);
        when(snapshot.baselineRef()).thenReturn(MAIN);
        when(snapshot.baselineCommit()).thenReturn(commit);
        return snapshot;
    }

    private RepositoryCommitId createSecondCommitAndMoveBareMain() throws Exception {
        Files.writeString(sourceRepository.resolve("README.md"), "second\n", StandardCharsets.UTF_8);
        runRequired(sourceRepository, "git", "add", "README.md");
        runRequired(sourceRepository, "git", "commit", "-m", "second fixture");
        RepositoryCommitId secondCommit = new RepositoryCommitId(runRequired(
                        sourceRepository, "git", "rev-parse", "HEAD")
                .trim());
        runRequired(
                sourceRepository,
                "git",
                "push",
                bareRepository.toString(),
                "HEAD:refs/heads/main");
        return secondCommit;
    }

    private void copyWorkingRepositoryToManagedCandidate() throws Exception {
        runRequired(
                temporaryDirectory,
                "git",
                "clone",
                sourceRepository.toString(),
                bareRepository.toString());
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(HOST_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    && process.exitValue() == 0;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static String runRequired(Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(HOST_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("Fixture command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Fixture command failed: " + output);
        }
        return output;
    }
}
