package io.crewscope.infrastructure.workspace;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executable M4-S02 fixture for the managed Git repository and worktree protocol. */
class GitWorkspaceM4S02IntegrationTest {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
  private static final String REPOSITORY_KEY = "repository-01";

  @TempDir Path temporaryDirectory;

  private Fixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
    fixture = Fixture.create(temporaryDirectory);
  }

  @Test
  void resolverAcceptsOnlyManagedBareRepositoriesAndRejectsSymlinkEscape() throws Exception {
    assertEquals(fixture.bareRepository().toRealPath(), fixture.resolver().resolve(fixture.key()));

    assertThrows(IllegalArgumentException.class, () -> RepositoryKey.parse("../escape"));
    assertThrows(IllegalArgumentException.class, () -> RepositoryKey.parse("--upload-pack=evil"));

    Path outsideRepository = temporaryDirectory.resolve("outside.git");
    fixture
        .git()
        .runRequired(
            List.of(
                "git",
                "clone",
                "--bare",
                fixture.source().toString(),
                outsideRepository.toString()));
    Files.createSymbolicLink(fixture.managedRoot().resolve("escaped.git"), outsideRepository);

    assertThrows(
        ProtocolFailure.class, () -> fixture.resolver().resolve(RepositoryKey.parse("escaped")));

    Path nonBare = fixture.managedRoot().resolve("non-bare.git");
    fixture
        .git()
        .runRequired(List.of("git", "clone", fixture.source().toString(), nonBare.toString()));
    assertThrows(
        ProtocolFailure.class, () -> fixture.resolver().resolve(RepositoryKey.parse("non-bare")));
  }

  @Test
  void typedIdentifiersProduceDeterministicPathsAndRejectGitArgumentInjection() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);

    assertEquals(
        "crewscope/tasks/" + identity.taskExecutionId() + "/attempt-1", identity.branch().value());
    assertEquals(
        fixture
            .worktreeRoot()
            .toRealPath()
            .resolve(REPOSITORY_KEY)
            .resolve(identity.workspaceKey().value()),
        fixture.provisioner().worktreePath(identity));

    assertThrows(IllegalArgumentException.class, () -> CommitId.parse("HEAD"));
    assertThrows(IllegalArgumentException.class, () -> CommitId.parse("--help"));
    assertThrows(IllegalArgumentException.class, () -> BranchName.parse("--orphan"));
    assertThrows(IllegalArgumentException.class, () -> BranchName.parse("crewscope/tasks/../main"));
    assertThrows(IllegalArgumentException.class, () -> WorkspaceKey.parse("../../outside"));
  }

  @Test
  void twoWorkersRacingForTheSameWorkspaceHaveExactlyOneCreator() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);
    CountDownLatch firstWorkerOwnsLock = new CountDownLatch(1);
    CountDownLatch releaseFirstWorker = new CountDownLatch(1);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    CompletableFuture<ProvisionOutcome> first =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return fixture
                    .newProvisioner()
                    .provision(
                        identity,
                        fixture.baseline(),
                        stage -> {
                          if (stage == ProvisionStage.GIT_WORKTREE_CREATED) {
                            firstWorkerOwnsLock.countDown();
                            if (!releaseFirstWorker.await(10, TimeUnit.SECONDS)) {
                              throw new ProtocolFailure(
                                  "timed out waiting for the competing worker");
                            }
                          }
                        });
              } catch (Throwable failure) {
                firstFailure.set(failure);
                throw new RuntimeException(failure);
              }
            });

    assertTrue(firstWorkerOwnsLock.await(10, TimeUnit.SECONDS));
    assertThrows(
        WorkspaceBusyFailure.class,
        () -> fixture.newProvisioner().provision(identity, fixture.baseline(), StageHook.NONE));
    releaseFirstWorker.countDown();

    assertEquals(ProvisionOutcome.CREATED, first.get(10, TimeUnit.SECONDS));
    assertNull(firstFailure.get());
    assertEquals(RecoveryOutcome.ACTIVE, fixture.newProvisioner().recover(identity));
  }

  @Test
  void ordinaryProvisionFailureRemovesPartialWorktreeBranchAndMetadata() {
    WorkspaceIdentity identity = fixture.identity(1);

    assertThrows(
        ProtocolFailure.class,
        () ->
            fixture
                .provisioner()
                .provision(
                    identity,
                    fixture.baseline(),
                    stage -> {
                      if (stage == ProvisionStage.GIT_WORKTREE_CREATED) {
                        throw new IOException("injected ordinary failure");
                      }
                    }));

    assertFalse(Files.exists(fixture.provisioner().worktreePath(identity), NOFOLLOW_LINKS));
    assertFalse(
        fixture.git().referenceExists(fixture.bareRepository(), identity.branch().fullRef()));
    assertFalse(
        Files.exists(fixture.metadataStore().path(identity.workspaceKey()), NOFOLLOW_LINKS));
  }

  @Test
  void coldRecoveryKeepsValidWorkspaceAndRollsBackAbruptExitOrphan() throws Exception {
    WorkspaceIdentity active = fixture.identity(1);
    assertEquals(
        ProvisionOutcome.CREATED,
        fixture.provisioner().provision(active, fixture.baseline(), StageHook.NONE));
    assertEquals(RecoveryOutcome.ACTIVE, fixture.newProvisioner().recover(active));

    WorkspaceIdentity orphan = fixture.identity(2);
    assertThrows(
        SimulatedProcessExit.class,
        () ->
            fixture
                .provisioner()
                .provision(
                    orphan,
                    fixture.baseline(),
                    stage -> {
                      if (stage == ProvisionStage.GIT_WORKTREE_CREATED) {
                        throw new SimulatedProcessExit();
                      }
                    }));
    assertTrue(Files.isDirectory(fixture.provisioner().worktreePath(orphan), NOFOLLOW_LINKS));
    assertTrue(fixture.git().referenceExists(fixture.bareRepository(), orphan.branch().fullRef()));

    assertEquals(RecoveryOutcome.ORPHAN_ROLLED_BACK, fixture.newProvisioner().recover(orphan));
    assertFalse(Files.exists(fixture.provisioner().worktreePath(orphan), NOFOLLOW_LINKS));
    assertFalse(fixture.git().referenceExists(fixture.bareRepository(), orphan.branch().fullRef()));
    assertEquals(
        ProvisionOutcome.CREATED,
        fixture.newProvisioner().provision(orphan, fixture.baseline(), StageHook.NONE));
  }

  @Test
  void preExistingDirectoryResidueFailsClosedWithoutDeletingUnknownContent() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);
    Path target = fixture.provisioner().worktreePath(identity);
    Files.createDirectories(target);
    Files.writeString(
        target.resolve("owner.txt"), "not-created-by-crewscope", StandardCharsets.UTF_8);

    assertThrows(
        ProtocolFailure.class,
        () -> fixture.provisioner().provision(identity, fixture.baseline(), StageHook.NONE));
    assertEquals(
        "not-created-by-crewscope",
        Files.readString(target.resolve("owner.txt"), StandardCharsets.UTF_8));
  }

  @Test
  void wrongHeadAndBranchAreReportedCorruptWithoutAutomaticDeletion() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);
    fixture.provisioner().provision(identity, fixture.baseline(), StageHook.NONE);
    Path target = fixture.provisioner().worktreePath(identity);

    fixture
        .git()
        .runRequired(
            List.of(
                "git",
                "-C",
                target.toString(),
                "checkout",
                "--detach",
                fixture.previous().value()));

    assertEquals(RecoveryOutcome.CORRUPT, fixture.newProvisioner().recover(identity));
    assertTrue(Files.isDirectory(target, NOFOLLOW_LINKS));
  }

  @Test
  void invalidGitPointerIsReportedCorruptWithoutFollowingIt() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);
    fixture.provisioner().provision(identity, fixture.baseline(), StageHook.NONE);
    Path target = fixture.provisioner().worktreePath(identity);
    Path outside = temporaryDirectory.resolve("outside-gitdir");
    Files.createDirectories(outside);
    Files.writeString(target.resolve(".git"), "gitdir: " + outside + "\n", StandardCharsets.UTF_8);

    assertEquals(RecoveryOutcome.CORRUPT, fixture.newProvisioner().recover(identity));
    assertTrue(Files.isDirectory(outside));
  }

  @Test
  void worktreePathSymlinkEscapeFailsClosedWithoutTouchingOutsideDirectory() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);
    Path target = fixture.provisioner().worktreePath(identity);
    Path outside = temporaryDirectory.resolve("outside-workspace");
    Files.createDirectories(target.getParent());
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("sentinel.txt"), "outside", StandardCharsets.UTF_8);
    Files.createSymbolicLink(target, outside);

    assertThrows(
        ProtocolFailure.class,
        () -> fixture.provisioner().provision(identity, fixture.baseline(), StageHook.NONE));
    assertEquals(
        "outside", Files.readString(outside.resolve("sentinel.txt"), StandardCharsets.UTF_8));
  }

  @Test
  void archivePinsDeliveryCommitThenColdRecoveryFinishesCleanupIdempotently() throws Exception {
    WorkspaceIdentity identity = fixture.identity(1);
    fixture.provisioner().provision(identity, fixture.baseline(), StageHook.NONE);
    Path target = fixture.provisioner().worktreePath(identity);
    Files.writeString(target.resolve("README.md"), "delivery-content\n", StandardCharsets.UTF_8);

    assertThrows(
        SimulatedProcessExit.class,
        () ->
            fixture
                .provisioner()
                .archive(
                    identity,
                    stage -> {
                      if (stage == ArchiveStage.ARCHIVING_METADATA_PUBLISHED) {
                        throw new SimulatedProcessExit();
                      }
                    }));

    WorkspaceMetadata interrupted =
        fixture.metadataStore().read(identity.workspaceKey()).orElseThrow();
    assertEquals(WorkspaceState.ARCHIVING, interrupted.state());
    assertTrue(
        fixture.git().referenceExists(fixture.bareRepository(), identity.archiveRef().value()));
    assertTrue(Files.isDirectory(target, NOFOLLOW_LINKS));

    assertEquals(RecoveryOutcome.ARCHIVED, fixture.newProvisioner().recover(identity));
    WorkspaceMetadata archived =
        fixture.metadataStore().read(identity.workspaceKey()).orElseThrow();
    assertEquals(WorkspaceState.ARCHIVED, archived.state());
    assertFalse(Files.exists(target, NOFOLLOW_LINKS));
    assertFalse(
        fixture.git().referenceExists(fixture.bareRepository(), identity.branch().fullRef()));
    assertEquals(
        "delivery-content",
        fixture
            .git()
            .runRequired(
                List.of(
                    "git",
                    "--git-dir",
                    fixture.bareRepository().toString(),
                    "show",
                    identity.archiveRef().value() + ":README.md"))
            .strip());
    assertEquals(RecoveryOutcome.ARCHIVED, fixture.newProvisioner().recover(identity));
  }

  private static boolean commandSucceeds(String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException failure) {
      return false;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private record Fixture(
      Path source,
      Path managedRoot,
      Path bareRepository,
      Path worktreeRoot,
      RepositoryKey key,
      CommitId baseline,
      CommitId previous,
      TypedGit git,
      ManagedRepositoryResolver resolver,
      WorkspaceMetadataStore metadataStore,
      WorktreeProvisioner provisioner) {

    static Fixture create(Path root) throws Exception {
      Path commandHome = Files.createDirectories(root.resolve("command-home"));
      TypedGit git = new TypedGit(commandHome);
      Path source = Files.createDirectories(root.resolve("source"));
      git.runRequired(List.of("git", "init", "--initial-branch=main", source.toString()));
      git.runRequired(
          List.of("git", "-C", source.toString(), "config", "user.name", "CrewScope Fixture"));
      git.runRequired(
          List.of(
              "git", "-C", source.toString(), "config", "user.email", "fixture@crewscope.local"));
      Files.writeString(source.resolve("README.md"), "baseline-one\n", StandardCharsets.UTF_8);
      git.runRequired(List.of("git", "-C", source.toString(), "add", "README.md"));
      git.runRequired(List.of("git", "-C", source.toString(), "commit", "-m", "fixture one"));
      CommitId previous =
          CommitId.parse(
              git.runRequired(List.of("git", "-C", source.toString(), "rev-parse", "HEAD"))
                  .strip());
      Files.writeString(source.resolve("README.md"), "baseline-two\n", StandardCharsets.UTF_8);
      git.runRequired(List.of("git", "-C", source.toString(), "commit", "-am", "fixture two"));
      CommitId baseline =
          CommitId.parse(
              git.runRequired(List.of("git", "-C", source.toString(), "rev-parse", "HEAD"))
                  .strip());

      RepositoryKey key = RepositoryKey.parse(REPOSITORY_KEY);
      Path managedRoot = Files.createDirectories(root.resolve("managed-repositories"));
      Path bareRepository = managedRoot.resolve(key.value() + ".git");
      git.runRequired(
          List.of("git", "clone", "--bare", source.toString(), bareRepository.toString()));
      Path worktreeRoot = Files.createDirectories(root.resolve("worktrees"));
      Path registryRoot = Files.createDirectories(root.resolve("workspace-registry"));
      Path lockRoot = Files.createDirectories(root.resolve("workspace-locks"));
      ManagedRepositoryResolver resolver = new ManagedRepositoryResolver(managedRoot, git);
      WorkspaceMetadataStore metadataStore = new WorkspaceMetadataStore(registryRoot);
      WorktreeProvisioner provisioner =
          new WorktreeProvisioner(worktreeRoot, lockRoot, resolver, metadataStore, git);
      return new Fixture(
          source,
          managedRoot,
          bareRepository,
          worktreeRoot,
          key,
          baseline,
          previous,
          git,
          resolver,
          metadataStore,
          provisioner);
    }

    WorkspaceIdentity identity(int attempt) {
      UUID taskExecutionId =
          UUID.nameUUIDFromBytes(("m4-s02-task-" + attempt).getBytes(StandardCharsets.UTF_8));
      return WorkspaceIdentity.create(key, taskExecutionId, attempt);
    }

    WorktreeProvisioner newProvisioner() throws IOException {
      return new WorktreeProvisioner(
          worktreeRoot,
          provisioner.lockRoot(),
          new ManagedRepositoryResolver(managedRoot, git),
          new WorkspaceMetadataStore(metadataStore.root()),
          git);
    }
  }

  private record RepositoryKey(String value) {
    private static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    static RepositoryKey parse(String value) {
      if (value == null || !PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException("invalid repository key");
      }
      return new RepositoryKey(value);
    }
  }

  private record WorkspaceKey(String value) {
    private static final Pattern PATTERN = Pattern.compile("ws-[0-9a-f]{32}-a[1-9][0-9]{0,3}");

    static WorkspaceKey parse(String value) {
      if (value == null || !PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException("invalid workspace key");
      }
      return new WorkspaceKey(value);
    }
  }

  private record CommitId(String value) {
    private static final Pattern PATTERN = Pattern.compile("[0-9a-f]{40}");

    static CommitId parse(String value) {
      if (value == null || !PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException("invalid commit id");
      }
      return new CommitId(value);
    }
  }

  private record TreeId(String value) {
    private static final Pattern PATTERN = Pattern.compile("[0-9a-f]{40}");

    static TreeId parse(String value) {
      if (value == null || !PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException("invalid tree id");
      }
      return new TreeId(value);
    }
  }

  private record BranchName(String value) {
    private static final Pattern PATTERN =
        Pattern.compile("crewscope/tasks/[0-9a-f-]{36}/attempt-[1-9][0-9]{0,3}");

    static BranchName parse(String value) {
      if (value == null || !PATTERN.matcher(value).matches() || value.contains("..")) {
        throw new IllegalArgumentException("invalid managed branch");
      }
      return new BranchName(value);
    }

    String fullRef() {
      return "refs/heads/" + value;
    }
  }

  private record ArchiveRef(String value) {
    private static final Pattern PATTERN =
        Pattern.compile("refs/crewscope/archives/ws-[0-9a-f]{32}-a[1-9][0-9]{0,3}");

    static ArchiveRef parse(String value) {
      if (value == null || !PATTERN.matcher(value).matches()) {
        throw new IllegalArgumentException("invalid archive ref");
      }
      return new ArchiveRef(value);
    }
  }

  private record WorkspaceIdentity(
      RepositoryKey repositoryKey,
      UUID taskExecutionId,
      int attempt,
      WorkspaceKey workspaceKey,
      BranchName branch,
      ArchiveRef archiveRef) {

    static WorkspaceIdentity create(
        RepositoryKey repositoryKey, UUID taskExecutionId, int attempt) {
      if (attempt < 1 || attempt > 9999) {
        throw new IllegalArgumentException("attempt outside supported range");
      }
      String compactId = taskExecutionId.toString().replace("-", "");
      WorkspaceKey workspaceKey = WorkspaceKey.parse("ws-" + compactId + "-a" + attempt);
      return new WorkspaceIdentity(
          repositoryKey,
          taskExecutionId,
          attempt,
          workspaceKey,
          BranchName.parse("crewscope/tasks/" + taskExecutionId + "/attempt-" + attempt),
          ArchiveRef.parse("refs/crewscope/archives/" + workspaceKey.value()));
    }
  }

  private static final class TypedGit {
    private static final int OUTPUT_LIMIT = 256 * 1024;

    private final Path commandHome;

    private TypedGit(Path commandHome) {
      this.commandHome = commandHome;
    }

    String runRequired(List<String> arguments) throws IOException, InterruptedException {
      GitResult result = run(arguments);
      if (result.exitCode() != 0) {
        throw new GitFailure(result.classification(), result.output());
      }
      return result.output();
    }

    GitResult run(List<String> arguments) throws IOException, InterruptedException {
      if (arguments.isEmpty() || !"git".equals(arguments.get(0))) {
        throw new IllegalArgumentException("only fixed git argument arrays are accepted");
      }
      ProcessBuilder builder = new ProcessBuilder(List.copyOf(arguments));
      builder.redirectErrorStream(true);
      Map<String, String> environment = builder.environment();
      String path = environment.get("PATH");
      environment.clear();
      if (path != null) {
        environment.put("PATH", path);
      }
      environment.put("HOME", commandHome.toString());
      environment.put("GIT_CONFIG_NOSYSTEM", "1");
      environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
      environment.put("GIT_TERMINAL_PROMPT", "0");
      environment.put("LC_ALL", "C");
      environment.put("LANG", "C");
      environment.put("GIT_AUTHOR_NAME", "CrewScope Delivery");
      environment.put("GIT_AUTHOR_EMAIL", "delivery@crewscope.local");
      environment.put("GIT_COMMITTER_NAME", "CrewScope Delivery");
      environment.put("GIT_COMMITTER_EMAIL", "delivery@crewscope.local");

      Path outputFile = Files.createTempFile(commandHome, "git-output-", ".log");
      builder.redirectOutput(outputFile.toFile());
      try {
        Process process = builder.start();
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          throw new GitFailure(GitFailureClass.TIMEOUT, "git command timed out");
        }
        if (Files.size(outputFile) > OUTPUT_LIMIT) {
          throw new GitFailure(GitFailureClass.OUTPUT_LIMIT, "git output exceeded limit");
        }
        String text = Files.readString(outputFile, StandardCharsets.UTF_8);
        return new GitResult(
            process.exitValue(),
            text,
            process.exitValue() == 0 ? GitFailureClass.NONE : classify(text));
      } finally {
        Files.deleteIfExists(outputFile);
      }
    }

    boolean referenceExists(Path repository, String fullRef) {
      try {
        GitResult result =
            run(
                List.of(
                    "git",
                    "--git-dir",
                    repository.toString(),
                    "show-ref",
                    "--verify",
                    "--quiet",
                    fullRef));
        return result.exitCode() == 0;
      } catch (IOException failure) {
        throw new ProtocolFailure("unable to inspect git reference", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new ProtocolFailure("git reference inspection interrupted", failure);
      }
    }

    private static GitFailureClass classify(String output) {
      String normalized = output.toLowerCase();
      if (normalized.contains("not a git repository")) {
        return GitFailureClass.NOT_A_REPOSITORY;
      }
      if (normalized.contains("already exists") || normalized.contains("already checked out")) {
        return GitFailureClass.CONFLICT;
      }
      if (normalized.contains("unknown revision") || normalized.contains("not a valid object")) {
        return GitFailureClass.INVALID_REFERENCE;
      }
      return GitFailureClass.COMMAND_FAILED;
    }
  }

  private record GitResult(int exitCode, String output, GitFailureClass classification) {}

  private enum GitFailureClass {
    NONE,
    NOT_A_REPOSITORY,
    INVALID_REFERENCE,
    CONFLICT,
    TIMEOUT,
    OUTPUT_LIMIT,
    COMMAND_FAILED
  }

  private static final class ManagedRepositoryResolver {
    private final Path managedRoot;
    private final TypedGit git;

    private ManagedRepositoryResolver(Path managedRoot, TypedGit git) throws IOException {
      this.managedRoot = managedRoot.toRealPath();
      this.git = git;
    }

    Path resolve(RepositoryKey key) {
      Path candidate = managedRoot.resolve(key.value() + ".git").normalize();
      if (!candidate.startsWith(managedRoot) || Files.isSymbolicLink(candidate)) {
        throw new ProtocolFailure("repository path escaped managed root");
      }
      try {
        Path canonical = candidate.toRealPath(NOFOLLOW_LINKS);
        if (!canonical.startsWith(managedRoot) || Files.isSymbolicLink(canonical)) {
          throw new ProtocolFailure("repository path escaped managed root");
        }
        String bare =
            git.runRequired(
                    List.of(
                        "git",
                        "--git-dir",
                        canonical.toString(),
                        "rev-parse",
                        "--is-bare-repository"))
                .strip();
        if (!"true".equals(bare)) {
          throw new ProtocolFailure("managed repository is not bare");
        }
        return canonical;
      } catch (IOException failure) {
        throw new ProtocolFailure("managed repository cannot be resolved", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new ProtocolFailure("repository resolution interrupted", failure);
      }
    }
  }

  private static final class WorktreeProvisioner {
    private final Path worktreeRoot;
    private final Path lockRoot;
    private final ManagedRepositoryResolver resolver;
    private final WorkspaceMetadataStore metadataStore;
    private final TypedGit git;

    private WorktreeProvisioner(
        Path worktreeRoot,
        Path lockRoot,
        ManagedRepositoryResolver resolver,
        WorkspaceMetadataStore metadataStore,
        TypedGit git)
        throws IOException {
      this.worktreeRoot = worktreeRoot.toRealPath();
      this.lockRoot = lockRoot.toRealPath();
      this.resolver = resolver;
      this.metadataStore = metadataStore;
      this.git = git;
    }

    Path lockRoot() {
      return lockRoot;
    }

    Path worktreePath(WorkspaceIdentity identity) {
      Path target =
          worktreeRoot
              .resolve(identity.repositoryKey().value())
              .resolve(identity.workspaceKey().value())
              .normalize();
      if (!target.startsWith(worktreeRoot)) {
        throw new ProtocolFailure("worktree path escaped root");
      }
      return target;
    }

    ProvisionOutcome provision(WorkspaceIdentity identity, CommitId baseline, StageHook stageHook) {
      Path repository = resolver.resolve(identity.repositoryKey());
      Path target = worktreePath(identity);
      try (WorkspacePathLock ignored = acquire(identity.workspaceKey())) {
        ensurePathHasNoSymlink(target);
        Optional<WorkspaceMetadata> existing = metadataStore.read(identity.workspaceKey());
        if (existing.isPresent()) {
          if (validateActive(identity, repository, target, existing.orElseThrow())) {
            return ProvisionOutcome.ALREADY_ACTIVE;
          }
          throw new ProtocolFailure("existing workspace metadata is not valid");
        }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
          throw new ProtocolFailure("unowned workspace path residue exists");
        }
        if (git.referenceExists(repository, identity.branch().fullRef())) {
          throw new ProtocolFailure("managed branch already exists without workspace metadata");
        }

        boolean worktreeCreated = false;
        try {
          Files.createDirectories(target.getParent());
          git.runRequired(
              List.of(
                  "git",
                  "--git-dir",
                  repository.toString(),
                  "worktree",
                  "add",
                  "-b",
                  identity.branch().value(),
                  target.toString(),
                  baseline.value()));
          worktreeCreated = true;
          stageHook.after(ProvisionStage.GIT_WORKTREE_CREATED);
          WorkspaceMetadata metadata =
              WorkspaceMetadata.active(identity, repository, target, baseline);
          if (!validateActive(identity, repository, target, metadata)) {
            throw new ProtocolFailure("new workspace failed fingerprint validation");
          }
          metadataStore.write(metadata);
          stageHook.after(ProvisionStage.ACTIVE_METADATA_PUBLISHED);
          return ProvisionOutcome.CREATED;
        } catch (SimulatedProcessExit failure) {
          // This models abrupt JVM termination: the OS releases the lock but no rollback runs.
          throw failure;
        } catch (Exception failure) {
          if (worktreeCreated) {
            rollback(repository, target, identity.branch());
          }
          metadataStore.delete(identity.workspaceKey());
          throw new ProtocolFailure("workspace provisioning failed", failure);
        }
      }
    }

    RecoveryOutcome recover(WorkspaceIdentity identity) {
      Path repository = resolver.resolve(identity.repositoryKey());
      Path target = worktreePath(identity);
      try (WorkspacePathLock ignored = acquire(identity.workspaceKey())) {
        Optional<WorkspaceMetadata> metadata = metadataStore.read(identity.workspaceKey());
        if (metadata.isEmpty()) {
          if (!Files.exists(target, NOFOLLOW_LINKS)
              && !git.referenceExists(repository, identity.branch().fullRef())) {
            return RecoveryOutcome.ABSENT;
          }
          if (isExpectedGitWorkspace(identity, repository, target)) {
            rollback(repository, target, identity.branch());
            return RecoveryOutcome.ORPHAN_ROLLED_BACK;
          }
          return RecoveryOutcome.CORRUPT;
        }

        WorkspaceMetadata stored = metadata.orElseThrow();
        if (stored.state() == WorkspaceState.ARCHIVED) {
          return archiveReferenceMatches(identity, repository, stored)
              ? RecoveryOutcome.ARCHIVED
              : RecoveryOutcome.CORRUPT;
        }
        if (stored.state() == WorkspaceState.ARCHIVING) {
          if (!archiveReferenceMatches(identity, repository, stored)) {
            return RecoveryOutcome.CORRUPT;
          }
          finishArchiveCleanup(identity, repository, target, stored);
          return RecoveryOutcome.ARCHIVED;
        }
        return validateActive(identity, repository, target, stored)
            ? RecoveryOutcome.ACTIVE
            : RecoveryOutcome.CORRUPT;
      }
    }

    ArchiveOutcome archive(WorkspaceIdentity identity, ArchiveHook archiveHook) {
      Path repository = resolver.resolve(identity.repositoryKey());
      Path target = worktreePath(identity);
      try (WorkspacePathLock ignored = acquire(identity.workspaceKey())) {
        WorkspaceMetadata active = metadataStore.read(identity.workspaceKey()).orElseThrow();
        if (active.state() == WorkspaceState.ARCHIVED) {
          return ArchiveOutcome.ALREADY_ARCHIVED;
        }
        if (!validateActive(identity, repository, target, active)) {
          throw new ProtocolFailure("workspace cannot be archived because its fingerprint changed");
        }
        try {
          git.runRequired(List.of("git", "-C", target.toString(), "add", "--all"));
          TreeId deliveryTree =
              TreeId.parse(
                  git.runRequired(List.of("git", "-C", target.toString(), "write-tree")).strip());
          // commit-tree preserves the active worktree branch at its baseline. A crash before the
          // archive ref is published therefore leaves a valid, retryable active workspace.
          CommitId delivery =
              CommitId.parse(
                  git.runRequired(
                          List.of(
                              "git",
                              "--git-dir",
                              repository.toString(),
                              "commit-tree",
                              deliveryTree.value(),
                              "-p",
                              active.baselineCommit(),
                              "-m",
                              "CrewScope delivery " + identity.workspaceKey().value()))
                      .strip());
          archiveHook.after(ArchiveStage.DELIVERY_COMMIT_CREATED);
          git.runRequired(
              List.of(
                  "git",
                  "--git-dir",
                  repository.toString(),
                  "update-ref",
                  identity.archiveRef().value(),
                  delivery.value()));
          archiveHook.after(ArchiveStage.ARCHIVE_REFERENCE_PINNED);
          WorkspaceMetadata archiving = active.archiving(delivery);
          metadataStore.write(archiving);
          archiveHook.after(ArchiveStage.ARCHIVING_METADATA_PUBLISHED);
          finishArchiveCleanup(identity, repository, target, archiving);
          archiveHook.after(ArchiveStage.ARCHIVED);
          return ArchiveOutcome.ARCHIVED;
        } catch (SimulatedProcessExit failure) {
          throw failure;
        } catch (IOException | InterruptedException failure) {
          if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          throw new ProtocolFailure("workspace archive failed", failure);
        }
      }
    }

    private WorkspacePathLock acquire(WorkspaceKey workspaceKey) {
      try {
        return WorkspacePathLock.tryAcquire(lockRoot.resolve(workspaceKey.value() + ".lock"))
            .orElseThrow(
                () -> new WorkspaceBusyFailure("workspace path is owned by another worker"));
      } catch (IOException failure) {
        throw new ProtocolFailure("workspace path lock failed", failure);
      }
    }

    private void ensurePathHasNoSymlink(Path target) {
      Path current = worktreeRoot;
      Path relative = worktreeRoot.relativize(target);
      for (Path segment : relative) {
        current = current.resolve(segment);
        if (Files.isSymbolicLink(current)) {
          throw new ProtocolFailure("worktree path contains a symbolic link");
        }
      }
    }

    private boolean validateActive(
        WorkspaceIdentity identity, Path repository, Path target, WorkspaceMetadata metadata) {
      if (metadata.state() != WorkspaceState.ACTIVE
          || !metadata.repositoryKey().equals(identity.repositoryKey().value())
          || !metadata.branch().equals(identity.branch().value())
          || !metadata.repositoryPath().equals(repository.toString())
          || !metadata.worktreePath().equals(target.toString())
          || !Files.isDirectory(target, NOFOLLOW_LINKS)
          || Files.isSymbolicLink(target)) {
        return false;
      }
      try {
        Path canonical = target.toRealPath(NOFOLLOW_LINKS);
        if (!canonical.startsWith(worktreeRoot) || !canonical.equals(target)) {
          return false;
        }
        String head =
            git.runRequired(List.of("git", "-C", target.toString(), "rev-parse", "HEAD")).strip();
        String branch =
            git.runRequired(
                    List.of("git", "-C", target.toString(), "symbolic-ref", "--short", "HEAD"))
                .strip();
        Path commonDirectory =
            Path.of(
                    git.runRequired(
                            List.of(
                                "git",
                                "-C",
                                target.toString(),
                                "rev-parse",
                                "--path-format=absolute",
                                "--git-common-dir"))
                        .strip())
                .toRealPath(NOFOLLOW_LINKS);
        return metadata.headCommit().equals(head)
            && metadata.baselineCommit().equals(head)
            && identity.branch().value().equals(branch)
            && repository.equals(commonDirectory);
      } catch (Exception failure) {
        return false;
      }
    }

    private boolean isExpectedGitWorkspace(
        WorkspaceIdentity identity, Path repository, Path target) {
      if (!Files.isDirectory(target, NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
        return false;
      }
      try {
        String branch =
            git.runRequired(
                    List.of("git", "-C", target.toString(), "symbolic-ref", "--short", "HEAD"))
                .strip();
        Path commonDirectory =
            Path.of(
                    git.runRequired(
                            List.of(
                                "git",
                                "-C",
                                target.toString(),
                                "rev-parse",
                                "--path-format=absolute",
                                "--git-common-dir"))
                        .strip())
                .toRealPath(NOFOLLOW_LINKS);
        return identity.branch().value().equals(branch) && repository.equals(commonDirectory);
      } catch (Exception failure) {
        return false;
      }
    }

    private boolean archiveReferenceMatches(
        WorkspaceIdentity identity, Path repository, WorkspaceMetadata metadata) {
      if (metadata.deliveryCommit() == null
          || !git.referenceExists(repository, identity.archiveRef().value())) {
        return false;
      }
      try {
        String archivedCommit =
            git.runRequired(
                    List.of(
                        "git",
                        "--git-dir",
                        repository.toString(),
                        "rev-parse",
                        "--verify",
                        identity.archiveRef().value() + "^{commit}"))
                .strip();
        return metadata.deliveryCommit().equals(archivedCommit);
      } catch (Exception failure) {
        return false;
      }
    }

    private void finishArchiveCleanup(
        WorkspaceIdentity identity, Path repository, Path target, WorkspaceMetadata archiving) {
      if (Files.exists(target, NOFOLLOW_LINKS)) {
        if (!isExpectedGitWorkspace(identity, repository, target)) {
          throw new ProtocolFailure("archive cleanup refused an unexpected worktree");
        }
        runRequiredUnchecked(
            List.of(
                "git",
                "--git-dir",
                repository.toString(),
                "worktree",
                "remove",
                "--force",
                target.toString()));
      }
      if (git.referenceExists(repository, identity.branch().fullRef())) {
        runRequiredUnchecked(
            List.of(
                "git",
                "--git-dir",
                repository.toString(),
                "update-ref",
                "-d",
                identity.branch().fullRef()));
      }
      metadataStore.write(archiving.archived());
    }

    private void rollback(Path repository, Path target, BranchName branch) {
      try {
        GitResult removal =
            git.run(
                List.of(
                    "git",
                    "--git-dir",
                    repository.toString(),
                    "worktree",
                    "remove",
                    "--force",
                    target.toString()));
        if (removal.exitCode() != 0 && Files.exists(target, NOFOLLOW_LINKS)) {
          deleteOwnedTree(target);
          git.runRequired(List.of("git", "--git-dir", repository.toString(), "worktree", "prune"));
        }
        if (git.referenceExists(repository, branch.fullRef())) {
          git.runRequired(
              List.of(
                  "git", "--git-dir", repository.toString(), "update-ref", "-d", branch.fullRef()));
        }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
          throw new ProtocolFailure("partial workspace rollback left a directory");
        }
      } catch (IOException failure) {
        throw new ProtocolFailure("partial workspace rollback failed", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new ProtocolFailure("partial workspace rollback interrupted", failure);
      }
    }

    private void runRequiredUnchecked(List<String> command) {
      try {
        git.runRequired(command);
      } catch (IOException failure) {
        throw new ProtocolFailure("git operation failed", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new ProtocolFailure("git operation interrupted", failure);
      }
    }

    private static void deleteOwnedTree(Path root) throws IOException {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static final class WorkspacePathLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorkspacePathLock(FileChannel channel, FileLock lock) {
      this.channel = channel;
      this.lock = lock;
    }

    static Optional<WorkspacePathLock> tryAcquire(Path lockPath) throws IOException {
      FileChannel channel =
          FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        FileLock lock = channel.tryLock();
        if (lock == null) {
          channel.close();
          return Optional.empty();
        }
        return Optional.of(new WorkspacePathLock(channel, lock));
      } catch (OverlappingFileLockException failure) {
        channel.close();
        return Optional.empty();
      }
    }

    @Override
    public void close() {
      try {
        lock.close();
        channel.close();
      } catch (IOException failure) {
        throw new ProtocolFailure("workspace path lock release failed", failure);
      }
    }
  }

  private static final class WorkspaceMetadataStore {
    private final Path root;

    private WorkspaceMetadataStore(Path root) throws IOException {
      this.root = root.toRealPath();
    }

    Path root() {
      return root;
    }

    Path path(WorkspaceKey key) {
      return root.resolve(key.value() + ".properties");
    }

    Optional<WorkspaceMetadata> read(WorkspaceKey key) {
      Path path = path(key);
      if (!Files.exists(path, NOFOLLOW_LINKS)) {
        return Optional.empty();
      }
      if (Files.isSymbolicLink(path)) {
        throw new ProtocolFailure("workspace metadata cannot be a symbolic link");
      }
      Properties properties = new Properties();
      try (var input = Files.newInputStream(path)) {
        properties.load(input);
        return Optional.of(WorkspaceMetadata.from(properties));
      } catch (IOException | RuntimeException failure) {
        throw new ProtocolFailure("workspace metadata is unreadable", failure);
      }
    }

    void write(WorkspaceMetadata metadata) {
      Path destination = path(WorkspaceKey.parse(metadata.workspaceKey()));
      Path staged = root.resolve(destination.getFileName() + ".tmp-" + UUID.randomUUID());
      Properties properties = metadata.toProperties();
      try (var output =
          Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        properties.store(output, "CrewScope M4-S02 workspace metadata");
      } catch (IOException failure) {
        throw new ProtocolFailure("workspace metadata staging failed", failure);
      }
      try {
        Files.move(
            staged,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException failure) {
        try {
          Files.deleteIfExists(staged);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw new ProtocolFailure("workspace metadata publication failed", failure);
      }
    }

    void delete(WorkspaceKey key) {
      try {
        Files.deleteIfExists(path(key));
      } catch (IOException failure) {
        throw new ProtocolFailure("workspace metadata deletion failed", failure);
      }
    }
  }

  private record WorkspaceMetadata(
      String workspaceKey,
      String repositoryKey,
      String repositoryPath,
      String worktreePath,
      String branch,
      String baselineCommit,
      String headCommit,
      WorkspaceState state,
      String deliveryCommit) {

    static WorkspaceMetadata active(
        WorkspaceIdentity identity, Path repository, Path worktree, CommitId baseline) {
      return new WorkspaceMetadata(
          identity.workspaceKey().value(),
          identity.repositoryKey().value(),
          repository.toString(),
          worktree.toString(),
          identity.branch().value(),
          baseline.value(),
          baseline.value(),
          WorkspaceState.ACTIVE,
          null);
    }

    WorkspaceMetadata archiving(CommitId delivery) {
      return new WorkspaceMetadata(
          workspaceKey,
          repositoryKey,
          repositoryPath,
          worktreePath,
          branch,
          baselineCommit,
          headCommit,
          WorkspaceState.ARCHIVING,
          delivery.value());
    }

    WorkspaceMetadata archived() {
      return new WorkspaceMetadata(
          workspaceKey,
          repositoryKey,
          repositoryPath,
          worktreePath,
          branch,
          baselineCommit,
          headCommit,
          WorkspaceState.ARCHIVED,
          deliveryCommit);
    }

    Properties toProperties() {
      Properties properties = new Properties();
      properties.setProperty("workspaceKey", workspaceKey);
      properties.setProperty("repositoryKey", repositoryKey);
      properties.setProperty("repositoryPath", repositoryPath);
      properties.setProperty("worktreePath", worktreePath);
      properties.setProperty("branch", branch);
      properties.setProperty("baselineCommit", baselineCommit);
      properties.setProperty("headCommit", headCommit);
      properties.setProperty("state", state.name());
      if (deliveryCommit != null) {
        properties.setProperty("deliveryCommit", deliveryCommit);
      }
      return properties;
    }

    static WorkspaceMetadata from(Properties properties) {
      return new WorkspaceMetadata(
          required(properties, "workspaceKey"),
          required(properties, "repositoryKey"),
          required(properties, "repositoryPath"),
          required(properties, "worktreePath"),
          required(properties, "branch"),
          required(properties, "baselineCommit"),
          required(properties, "headCommit"),
          WorkspaceState.valueOf(required(properties, "state")),
          properties.getProperty("deliveryCommit"));
    }

    private static String required(Properties properties, String key) {
      String value = properties.getProperty(key);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("missing workspace metadata property: " + key);
      }
      return value;
    }
  }

  private enum WorkspaceState {
    ACTIVE,
    ARCHIVING,
    ARCHIVED
  }

  private enum ProvisionOutcome {
    CREATED,
    ALREADY_ACTIVE
  }

  private enum RecoveryOutcome {
    ABSENT,
    ACTIVE,
    ARCHIVED,
    ORPHAN_ROLLED_BACK,
    CORRUPT
  }

  private enum ArchiveOutcome {
    ARCHIVED,
    ALREADY_ARCHIVED
  }

  private enum ProvisionStage {
    GIT_WORKTREE_CREATED,
    ACTIVE_METADATA_PUBLISHED
  }

  private enum ArchiveStage {
    DELIVERY_COMMIT_CREATED,
    ARCHIVE_REFERENCE_PINNED,
    ARCHIVING_METADATA_PUBLISHED,
    ARCHIVED
  }

  @FunctionalInterface
  private interface StageHook {
    StageHook NONE = ignored -> {};

    void after(ProvisionStage stage) throws Exception;
  }

  @FunctionalInterface
  private interface ArchiveHook {
    void after(ArchiveStage stage);
  }

  private static class ProtocolFailure extends RuntimeException {
    private ProtocolFailure(String message) {
      super(message);
    }

    private ProtocolFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class WorkspaceBusyFailure extends ProtocolFailure {
    private WorkspaceBusyFailure(String message) {
      super(message);
    }
  }

  private static final class GitFailure extends ProtocolFailure {
    private final GitFailureClass classification;

    private GitFailure(GitFailureClass classification, String message) {
      super(message);
      this.classification = classification;
    }

    @SuppressWarnings("unused")
    GitFailureClass classification() {
      return classification;
    }
  }

  private static final class SimulatedProcessExit extends Error {}
}
