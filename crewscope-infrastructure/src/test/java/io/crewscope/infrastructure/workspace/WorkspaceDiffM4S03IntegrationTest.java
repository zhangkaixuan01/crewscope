package io.crewscope.infrastructure.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executable M4-S03 fixture for WatchService hints, Git reconciliation and Diff replay. */
class WorkspaceDiffM4S03IntegrationTest {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
  private static final int PATCH_PREVIEW_BYTES = 2_048;
  private static final int PATCH_PREVIEW_LINES = 20;
  private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000004301");
  private static final UUID OTHER_WORKSPACE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000004302");
  private static final UUID STREAM_EPOCH = UUID.fromString("00000000-0000-0000-0000-000000004311");
  private static final byte[] CURSOR_SECRET =
      "crewscope-m4-s03-cursor-key-32bytes".getBytes(StandardCharsets.UTF_8);

  @TempDir Path temporaryDirectory;

  private GitFixture fixture;
  private GitDiffReconciler reconciler;

  @BeforeEach
  void setUp() throws Exception {
    Assumptions.assumeTrue(commandSucceeds("git", "--version"), "Git is required");
    fixture = GitFixture.create(temporaryDirectory);
    reconciler =
        new GitDiffReconciler(
            fixture.repository(), PATCH_PREVIEW_BYTES, PATCH_PREVIEW_LINES, fixture.git());
  }

  @Test
  void watchServiceProducesHintsForTrackedFileChanges() throws Exception {
    Path sourceDirectory = fixture.repository().resolve("src");
    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
      sourceDirectory.register(
          watcher,
          StandardWatchEventKinds.ENTRY_CREATE,
          StandardWatchEventKinds.ENTRY_MODIFY,
          StandardWatchEventKinds.ENTRY_DELETE);

      Files.writeString(
          sourceDirectory.resolve("Greeting.java"),
          GitFixture.changedGreeting(),
          StandardCharsets.UTF_8);

      WatchKey key = watcher.poll(5, TimeUnit.SECONDS);
      assertTrue(key != null, "WatchService must emit at least one scheduling hint");
      List<WatchEvent<?>> events = key.pollEvents();
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event.context().toString().equals("Greeting.java")
                          && Set.of(
                                  StandardWatchEventKinds.ENTRY_CREATE,
                                  StandardWatchEventKinds.ENTRY_MODIFY)
                              .contains(event.kind())));
      assertTrue(key.reset());
    }

    DiffManifest authoritative = reconciler.reconcileWorkingTree(fixture.baseline(), 1);
    assertEquals(
        List.of("src/Greeting.java"), authoritative.files().stream().map(DiffFile::path).toList());
    assertEquals(ChangeKind.MODIFIED, authoritative.files().get(0).kind());
  }

  @Test
  void droppedDuplicatedAndOutOfOrderHintsConvergeToAuthoritativeGitDiff() throws Exception {
    DiffCursorCodec cursorCodec = new DiffCursorCodec(CURSOR_SECRET);
    DiffEventLog eventLog = new DiffEventLog(WORKSPACE_ID, STREAM_EPOCH, cursorCodec);
    DiffProjection projection = new DiffProjection(WORKSPACE_ID, STREAM_EPOCH);

    DiffStreamEvent initial =
        eventLog.reset(reconciler.reconcileWorkingTree(fixture.baseline(), 1));
    assertTrue(
        eventLog.reconcile(reconciler.reconcileWorkingTree(fixture.baseline(), 99)).isEmpty(),
        "Periodic reconcile must not publish a new generation when content is unchanged");
    fixture.changeGreeting();
    DiffStreamEvent changed =
        eventLog.reconcile(reconciler.reconcileWorkingTree(fixture.baseline(), 2)).orElseThrow();
    fixture.applyFinalChanges();
    DiffManifest finalManifest = reconciler.reconcileWorkingTree(fixture.baseline(), 3);
    DiffStreamEvent completed = eventLog.reconcile(finalManifest).orElseThrow();

    assertEquals(ApplyOutcome.RESET, projection.apply(initial));
    assertEquals(ApplyOutcome.GAP, projection.apply(completed));
    assertEquals(ApplyOutcome.APPLIED, projection.apply(changed));
    assertEquals(ApplyOutcome.DUPLICATE, projection.apply(changed));
    assertEquals(ApplyOutcome.APPLIED, projection.apply(completed));

    assertEquals(finalManifest.files(), projection.snapshot().files());
    assertEquals(finalManifest.manifestHash(), projection.snapshot().manifestHash());
    assertEquals(completed.cursor(), projection.cursor());
  }

  @Test
  void opaqueCursorIsSignedAndBoundToWorkspaceEpochSequenceAndGeneration() {
    DiffCursorCodec codec = new DiffCursorCodec(CURSOR_SECRET);
    String cursor = codec.encode(new DiffCursor(WORKSPACE_ID, STREAM_EPOCH, 42, 7));

    assertFalse(cursor.contains(WORKSPACE_ID.toString()));
    assertFalse(cursor.contains("42"));
    assertEquals(
        new DiffCursor(WORKSPACE_ID, STREAM_EPOCH, 42, 7),
        codec.decode(cursor, WORKSPACE_ID, STREAM_EPOCH));
    assertThrows(CursorFailure.class, () -> codec.decode(cursor, OTHER_WORKSPACE_ID, STREAM_EPOCH));
    assertThrows(CursorFailure.class, () -> codec.decode(cursor, WORKSPACE_ID, UUID.randomUUID()));

    char replacement = cursor.charAt(cursor.length() - 1) == 'A' ? 'B' : 'A';
    String tampered = cursor.substring(0, cursor.length() - 1) + replacement;
    assertThrows(CursorFailure.class, () -> codec.decode(tampered, WORKSPACE_ID, STREAM_EPOCH));
  }

  @Test
  void resetEventRepairsProjectionGapWithoutApplyingFutureDelta() throws Exception {
    DiffEventLog eventLog =
        new DiffEventLog(WORKSPACE_ID, STREAM_EPOCH, new DiffCursorCodec(CURSOR_SECRET));
    DiffProjection projection = new DiffProjection(WORKSPACE_ID, STREAM_EPOCH);

    DiffStreamEvent initial =
        eventLog.reset(reconciler.reconcileWorkingTree(fixture.baseline(), 1));
    fixture.changeGreeting();
    eventLog.reconcile(reconciler.reconcileWorkingTree(fixture.baseline(), 2)).orElseThrow();
    fixture.applyFinalChanges();
    DiffManifest finalManifest = reconciler.reconcileWorkingTree(fixture.baseline(), 3);
    DiffStreamEvent futureDelta = eventLog.reconcile(finalManifest).orElseThrow();

    assertEquals(ApplyOutcome.RESET, projection.apply(initial));
    assertEquals(ApplyOutcome.GAP, projection.apply(futureDelta));
    assertEquals(initial.manifestHash(), projection.snapshot().manifestHash());

    DiffStreamEvent reset = eventLog.reset(finalManifest);
    assertEquals(ApplyOutcome.RESET, projection.apply(reset));
    assertEquals(finalManifest.files(), projection.snapshot().files());
    assertEquals(reset.cursor(), projection.cursor());
  }

  @Test
  void patchPreviewTruncationPreservesFullPatchHashStatsAndArtifact() throws Exception {
    fixture.applyFinalChanges();
    DiffManifest manifest = reconciler.reconcileWorkingTree(fixture.baseline(), 1);
    DiffFile large = file(manifest, "docs/large.txt");
    String fullPatch = reconciler.fullPatch(fixture.baseline(), null, large);

    assertTrue(large.patchTruncated());
    assertTrue(large.patchPreview().getBytes(StandardCharsets.UTF_8).length <= PATCH_PREVIEW_BYTES);
    assertTrue(large.patchPreview().lines().count() <= PATCH_PREVIEW_LINES);
    assertEquals(sha256(fullPatch), large.patchSha256());
    assertEquals(40, large.additions());
    assertEquals(0, large.deletions());

    PatchArtifact artifact = reconciler.fullArtifact(fixture.baseline(), null);
    assertTrue(artifact.content().contains("+line-40"));
    assertEquals(sha256(artifact.content()), artifact.sha256());
    assertTrue(artifact.content().length() > large.patchPreview().length());
  }

  @Test
  void finalDiffIsDerivedFromDeliveryCommitAndRemainsImmutable() throws Exception {
    fixture.applyFinalChanges();
    fixture.git().runRequired("git", "-C", fixture.repository().toString(), "add", "--all");
    fixture
        .git()
        .runRequired(
            "git", "-C", fixture.repository().toString(), "commit", "-m", "M4-S03 delivery");
    CommitId delivery =
        CommitId.parse(
            fixture
                .git()
                .runRequired("git", "-C", fixture.repository().toString(), "rev-parse", "HEAD")
                .strip());

    FinalDiffArtifact finalized =
        new DiffFinalizer(reconciler).finalizeDiff(WORKSPACE_ID, fixture.baseline(), delivery, 9);
    assertEquals(5, finalized.manifest().files().size());
    assertEquals(delivery, finalized.deliveryCommit());
    assertEquals(finalized.artifact().sha256(), sha256(finalized.artifact().content()));
    assertEquals(finalized.finalHash(), finalized.recomputeHash());

    Files.writeString(
        fixture.repository().resolve("src/Greeting.java"),
        "post-finalization mutation\n",
        StandardCharsets.UTF_8);
    DiffManifest current = reconciler.reconcileWorkingTree(fixture.baseline(), 10);

    assertNotEquals(finalized.manifest().manifestHash(), current.manifestHash());
    assertEquals(5, finalized.manifest().files().size());
    assertEquals(finalized.finalHash(), finalized.recomputeHash());
  }

  @Test
  void sharedFrontendFixtureReplaysToTheDeclaredFinalProjection() throws Exception {
    JsonNode fixtureJson =
        new ObjectMapper().readTree(Files.readString(sharedFixturePath(), StandardCharsets.UTF_8));
    assertEquals(
        "crewscope.diff-stream.fixture/v1", fixtureJson.required("schemaVersion").asText());

    DiffProjection projection = new DiffProjection(WORKSPACE_ID, STREAM_EPOCH);
    Map<String, DiffStreamEvent> events = new HashMap<>();
    for (JsonNode eventNode : fixtureJson.required("events")) {
      DiffStreamEvent event = fixtureEvent(eventNode);
      events.put(eventNode.required("id").asText(), event);
    }
    List<ApplyOutcome> outcomes = new ArrayList<>();
    for (JsonNode delivery : fixtureJson.required("deliveryOrder")) {
      outcomes.add(projection.apply(events.get(delivery.asText())));
    }

    assertEquals(
        List.of(
            ApplyOutcome.RESET,
            ApplyOutcome.GAP,
            ApplyOutcome.APPLIED,
            ApplyOutcome.DUPLICATE,
            ApplyOutcome.APPLIED,
            ApplyOutcome.RESET),
        outcomes);
    JsonNode expected = fixtureJson.required("expectedProjection");
    assertEquals(expected.required("generation").asLong(), projection.generation());
    assertEquals(
        stringList(expected.required("paths")),
        projection.snapshot().files().stream().map(DiffFile::path).toList());
    assertEquals(expected.required("manifestHash").asText(), projection.snapshot().manifestHash());
    assertTrue(compareUnicodeCodePoints("\uE000", "\uD800\uDC00") < 0);
  }

  private static DiffStreamEvent fixtureEvent(JsonNode node) {
    EventKind kind = EventKind.valueOf(node.required("kind").asText());
    List<DiffFile> upserts = new ArrayList<>();
    for (JsonNode file : node.required("files")) {
      upserts.add(
          new DiffFile(
              file.required("path").asText(),
              nullableText(file.get("oldPath")),
              ChangeKind.valueOf(file.required("kind").asText()),
              file.required("additions").asInt(),
              file.required("deletions").asInt(),
              file.required("binary").asBoolean(),
              file.required("patchTruncated").asBoolean(),
              file.required("patchSha256").asText(),
              file.required("patchPreview").asText()));
    }
    List<String> removals = stringList(node.required("removals"));
    return new DiffStreamEvent(
        WORKSPACE_ID,
        STREAM_EPOCH,
        node.required("sequence").asLong(),
        node.required("generation").asLong(),
        node.required("cursor").asText(),
        kind,
        List.copyOf(upserts),
        removals,
        node.required("manifestHash").asText());
  }

  private static List<String> stringList(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return List.copyOf(values);
  }

  private static String nullableText(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private Path sharedFixturePath() {
    Path fromRoot =
        Path.of("crewscope-web", "src", "spikes", "m4", "fixtures", "diff-stream-v1.json")
            .toAbsolutePath();
    if (Files.isRegularFile(fromRoot)) {
      return fromRoot;
    }
    Path fromModule =
        Path.of("..", "crewscope-web", "src", "spikes", "m4", "fixtures", "diff-stream-v1.json")
            .toAbsolutePath();
    if (Files.isRegularFile(fromModule)) {
      return fromModule;
    }
    throw new IllegalStateException("shared M4-S03 fixture is missing");
  }

  private static DiffFile file(DiffManifest manifest, String path) {
    return manifest.files().stream()
        .filter(candidate -> candidate.path().equals(path))
        .findFirst()
        .orElseThrow();
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

  private record GitFixture(Path repository, CommitId baseline, CommandExecutor git) {

    static GitFixture create(Path root) throws Exception {
      Path repository = Files.createDirectories(root.resolve("repository"));
      Path commandHome = Files.createDirectories(root.resolve("command-home"));
      CommandExecutor git = new CommandExecutor(commandHome);
      git.runRequired("git", "init", "--initial-branch=main", repository.toString());
      git.runRequired(
          "git", "-C", repository.toString(), "config", "user.name", "CrewScope Fixture");
      git.runRequired(
          "git", "-C", repository.toString(), "config", "user.email", "fixture@crewscope.local");
      Files.createDirectories(repository.resolve("src"));
      Files.writeString(
          repository.resolve("src/Greeting.java"), baselineGreeting(), StandardCharsets.UTF_8);
      Files.writeString(repository.resolve("README.md"), "# M4-S03\n", StandardCharsets.UTF_8);
      Files.writeString(repository.resolve("obsolete.txt"), "obsolete\n", StandardCharsets.UTF_8);
      git.runRequired("git", "-C", repository.toString(), "add", "--all");
      git.runRequired("git", "-C", repository.toString(), "commit", "-m", "M4-S03 baseline");
      CommitId baseline =
          CommitId.parse(
              git.runRequired("git", "-C", repository.toString(), "rev-parse", "HEAD").strip());
      return new GitFixture(repository.toRealPath(), baseline, git);
    }

    void changeGreeting() throws IOException {
      Files.writeString(
          repository.resolve("src/Greeting.java"), changedGreeting(), StandardCharsets.UTF_8);
    }

    void applyFinalChanges() throws Exception {
      changeGreeting();
      Files.writeString(
          repository.resolve("src/Feature.java"),
          """
          package demo;

          final class Feature {
            static boolean enabled() {
              return true;
            }
          }
          """,
          StandardCharsets.UTF_8);
      Path docs = Files.createDirectories(repository.resolve("docs"));
      Files.move(repository.resolve("README.md"), docs.resolve("README.md"));
      StringBuilder large = new StringBuilder();
      for (int line = 1; line <= 40; line++) {
        large.append("line-").append(String.format("%02d", line)).append('\n');
      }
      Files.writeString(docs.resolve("large.txt"), large, StandardCharsets.UTF_8);
      Files.delete(repository.resolve("obsolete.txt"));
      // Staging makes new files and rename detection visible without a delivery commit.
      git.runRequired("git", "-C", repository.toString(), "add", "--all");
    }

    static String baselineGreeting() {
      return """
      package demo;

      final class Greeting {
        static String value() {
          return "before";
        }
      }
      """;
    }

    static String changedGreeting() {
      return """
      package demo;

      final class Greeting {
        static String value(String name) {
          return "hello " + name;
        }
      }
      """;
    }
  }

  private static final class GitDiffReconciler {
    private final Path repository;
    private final int previewBytes;
    private final int previewLines;
    private final CommandExecutor git;

    private GitDiffReconciler(
        Path repository, int previewBytes, int previewLines, CommandExecutor git) {
      this.repository = repository;
      this.previewBytes = previewBytes;
      this.previewLines = previewLines;
      this.git = git;
    }

    DiffManifest reconcileWorkingTree(CommitId baseline, long generation) {
      return reconcile(baseline, null, generation);
    }

    DiffManifest reconcileCommits(CommitId baseline, CommitId delivery, long generation) {
      return reconcile(baseline, delivery, generation);
    }

    private DiffManifest reconcile(CommitId baseline, CommitId delivery, long generation) {
      List<NameStatus> statuses = nameStatuses(baseline, delivery);
      List<DiffFile> files =
          statuses.stream()
              .map(status -> describe(baseline, delivery, status))
              .sorted(
                  Comparator.comparing(
                      DiffFile::path,
                      WorkspaceDiffM4S03IntegrationTest::compareUnicodeCodePoints))
              .toList();
      return DiffManifest.create(generation, files);
    }

    PatchArtifact fullArtifact(CommitId baseline, CommitId delivery) {
      List<String> command = diffCommand(baseline, delivery);
      command.add("--");
      String content = git.runRequired(command.toArray(String[]::new));
      return new PatchArtifact(content, sha256(content));
    }

    String fullPatch(CommitId baseline, CommitId delivery, DiffFile file) {
      List<String> command = diffCommand(baseline, delivery);
      command.add("--");
      if (file.oldPath() != null) {
        command.add(file.oldPath());
      }
      command.add(file.path());
      return git.runRequired(command.toArray(String[]::new));
    }

    private List<NameStatus> nameStatuses(CommitId baseline, CommitId delivery) {
      List<String> command = new ArrayList<>();
      command.add("git");
      command.add("-C");
      command.add(repository.toString());
      command.add("diff");
      command.add("--name-status");
      command.add("-z");
      command.add("--find-renames");
      command.add(baseline.value());
      if (delivery != null) {
        command.add(delivery.value());
      }
      command.add("--");
      String output = git.runRequired(command.toArray(String[]::new));
      String[] tokens = output.split("\\u0000", -1);
      List<NameStatus> statuses = new ArrayList<>();
      int index = 0;
      while (index < tokens.length && !tokens[index].isEmpty()) {
        String statusToken = tokens[index++];
        ChangeKind kind = ChangeKind.from(statusToken.charAt(0));
        if (kind == ChangeKind.RENAMED || kind == ChangeKind.COPIED) {
          String oldPath = tokens[index++];
          String path = tokens[index++];
          statuses.add(new NameStatus(path, oldPath, kind));
        } else {
          statuses.add(new NameStatus(tokens[index++], null, kind));
        }
      }
      return List.copyOf(statuses);
    }

    private DiffFile describe(CommitId baseline, CommitId delivery, NameStatus status) {
      DiffFile probe =
          new DiffFile(status.path(), status.oldPath(), status.kind(), 0, 0, false, false, "", "");
      String patch = fullPatch(baseline, delivery, probe);
      LineStats stats = LineStats.from(patch);
      PatchPreview preview = PatchPreview.create(patch, previewBytes, previewLines);
      return new DiffFile(
          status.path(),
          status.oldPath(),
          status.kind(),
          stats.additions(),
          stats.deletions(),
          stats.binary(),
          preview.truncated(),
          sha256(patch),
          preview.content());
    }

    private List<String> diffCommand(CommitId baseline, CommitId delivery) {
      List<String> command = new ArrayList<>();
      command.add("git");
      command.add("-C");
      command.add(repository.toString());
      command.add("diff");
      command.add("--binary");
      command.add("--no-ext-diff");
      command.add("--find-renames");
      command.add("--unified=3");
      command.add(baseline.value());
      if (delivery != null) {
        command.add(delivery.value());
      }
      return command;
    }
  }

  private static final class DiffEventLog {
    private final UUID workspaceId;
    private final UUID streamEpoch;
    private final DiffCursorCodec cursorCodec;
    private long sequence;
    private DiffManifest previous;

    private DiffEventLog(UUID workspaceId, UUID streamEpoch, DiffCursorCodec cursorCodec) {
      this.workspaceId = workspaceId;
      this.streamEpoch = streamEpoch;
      this.cursorCodec = cursorCodec;
    }

    DiffStreamEvent reset(DiffManifest manifest) {
      sequence++;
      previous = manifest;
      return event(EventKind.RESET, manifest.files(), List.of(), manifest);
    }

    java.util.Optional<DiffStreamEvent> reconcile(DiffManifest manifest) {
      if (previous != null && previous.manifestHash().equals(manifest.manifestHash())) {
        return java.util.Optional.empty();
      }
      sequence++;
      Map<String, DiffFile> before = byPath(previous == null ? List.of() : previous.files());
      Map<String, DiffFile> after = byPath(manifest.files());
      List<DiffFile> upserts =
          after.values().stream()
              .filter(file -> !file.equals(before.get(file.path())))
              .sorted(
                  Comparator.comparing(
                      DiffFile::path,
                      WorkspaceDiffM4S03IntegrationTest::compareUnicodeCodePoints))
              .toList();
      List<String> removals =
          before.keySet().stream()
              .filter(path -> !after.containsKey(path))
              .sorted(WorkspaceDiffM4S03IntegrationTest::compareUnicodeCodePoints)
              .toList();
      previous = manifest;
      return java.util.Optional.of(event(EventKind.DELTA, upserts, removals, manifest));
    }

    private DiffStreamEvent event(
        EventKind kind, List<DiffFile> upserts, List<String> removals, DiffManifest manifest) {
      String cursor =
          cursorCodec.encode(
              new DiffCursor(workspaceId, streamEpoch, sequence, manifest.generation()));
      return new DiffStreamEvent(
          workspaceId,
          streamEpoch,
          sequence,
          manifest.generation(),
          cursor,
          kind,
          List.copyOf(upserts),
          List.copyOf(removals),
          manifest.manifestHash());
    }
  }

  private static final class DiffProjection {
    private final UUID workspaceId;
    private final UUID streamEpoch;
    private final Map<String, DiffFile> files = new LinkedHashMap<>();
    private long sequence;
    private long generation;
    private String cursor;
    private String manifestHash = sha256("");

    private DiffProjection(UUID workspaceId, UUID streamEpoch) {
      this.workspaceId = workspaceId;
      this.streamEpoch = streamEpoch;
    }

    ApplyOutcome apply(DiffStreamEvent event) {
      if (!workspaceId.equals(event.workspaceId()) || !streamEpoch.equals(event.streamEpoch())) {
        throw new ProtocolFailure("diff event scope does not match projection");
      }
      if (event.sequence() <= sequence) {
        return ApplyOutcome.DUPLICATE;
      }
      if (event.kind() == EventKind.DELTA && event.sequence() != sequence + 1) {
        return ApplyOutcome.GAP;
      }
      if (event.generation() < generation) {
        return ApplyOutcome.STALE;
      }
      if (event.kind() == EventKind.RESET) {
        files.clear();
      }
      event.removals().forEach(files::remove);
      event.files().forEach(file -> files.put(file.path(), file));
      sequence = event.sequence();
      generation = event.generation();
      cursor = event.cursor();
      manifestHash = event.manifestHash();
      return event.kind() == EventKind.RESET ? ApplyOutcome.RESET : ApplyOutcome.APPLIED;
    }

    DiffManifest snapshot() {
      return new DiffManifest(
          generation,
          files.values().stream()
              .sorted(
                  Comparator.comparing(
                      DiffFile::path,
                      WorkspaceDiffM4S03IntegrationTest::compareUnicodeCodePoints))
              .toList(),
          manifestHash);
    }

    long generation() {
      return generation;
    }

    String cursor() {
      return cursor;
    }
  }

  private static final class DiffCursorCodec {
    private final SecretKeySpec key;

    private DiffCursorCodec(byte[] secret) {
      if (secret.length < 32) {
        throw new IllegalArgumentException("cursor secret must contain at least 32 bytes");
      }
      key = new SecretKeySpec(secret.clone(), "HmacSHA256");
    }

    String encode(DiffCursor cursor) {
      byte[] payload = cursor.payload().getBytes(StandardCharsets.UTF_8);
      return base64(payload) + "." + base64(mac(payload));
    }

    DiffCursor decode(String token, UUID expectedWorkspace, UUID expectedEpoch) {
      try {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
          throw new CursorFailure("invalid cursor format");
        }
        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
        if (!MessageDigest.isEqual(signature, mac(payload))) {
          throw new CursorFailure("invalid cursor signature");
        }
        DiffCursor cursor = DiffCursor.parse(new String(payload, StandardCharsets.UTF_8));
        if (!expectedWorkspace.equals(cursor.workspaceId())
            || !expectedEpoch.equals(cursor.streamEpoch())) {
          throw new CursorFailure("cursor scope mismatch");
        }
        return cursor;
      } catch (IllegalArgumentException failure) {
        throw new CursorFailure("invalid cursor encoding", failure);
      }
    }

    private byte[] mac(byte[] value) {
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(value);
      } catch (GeneralSecurityException failure) {
        throw new IllegalStateException("HmacSHA256 unavailable", failure);
      }
    }

    private static String base64(byte[] value) {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
  }

  private static final class DiffFinalizer {
    private final GitDiffReconciler reconciler;

    private DiffFinalizer(GitDiffReconciler reconciler) {
      this.reconciler = reconciler;
    }

    FinalDiffArtifact finalizeDiff(
        UUID workspaceId, CommitId baseline, CommitId delivery, long generation) {
      DiffManifest manifest = reconciler.reconcileCommits(baseline, delivery, generation);
      PatchArtifact artifact = reconciler.fullArtifact(baseline, delivery);
      return FinalDiffArtifact.create(workspaceId, baseline, delivery, manifest, artifact);
    }
  }

  private static final class CommandExecutor {
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private final Path commandHome;

    private CommandExecutor(Path commandHome) {
      this.commandHome = commandHome;
    }

    String runRequired(String... command) {
      Path outputFile = null;
      try {
        outputFile = Files.createTempFile(commandHome, "git-output-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(outputFile.toFile());
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
        environment.put("GIT_AUTHOR_NAME", "CrewScope Diff Fixture");
        environment.put("GIT_AUTHOR_EMAIL", "diff@crewscope.local");
        environment.put("GIT_COMMITTER_NAME", "CrewScope Diff Fixture");
        environment.put("GIT_COMMITTER_EMAIL", "diff@crewscope.local");
        Process process = builder.start();
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          throw new ProtocolFailure("git command timed out");
        }
        if (Files.size(outputFile) > OUTPUT_LIMIT) {
          throw new ProtocolFailure("git command output exceeded limit");
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
          throw new ProtocolFailure("git command failed: " + output);
        }
        return output;
      } catch (IOException failure) {
        throw new ProtocolFailure("git command failed", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new ProtocolFailure("git command interrupted", failure);
      } finally {
        if (outputFile != null) {
          try {
            Files.deleteIfExists(outputFile);
          } catch (IOException failure) {
            throw new ProtocolFailure("git output cleanup failed", failure);
          }
        }
      }
    }
  }

  private record CommitId(String value) {
    static CommitId parse(String value) {
      if (value == null || !value.matches("[0-9a-f]{40}")) {
        throw new IllegalArgumentException("invalid commit id");
      }
      return new CommitId(value);
    }
  }

  private record NameStatus(String path, String oldPath, ChangeKind kind) {}

  private record DiffFile(
      String path,
      String oldPath,
      ChangeKind kind,
      int additions,
      int deletions,
      boolean binary,
      boolean patchTruncated,
      String patchSha256,
      String patchPreview) {}

  private record DiffManifest(long generation, List<DiffFile> files, String manifestHash) {
    static DiffManifest create(long generation, List<DiffFile> files) {
      List<DiffFile> sorted =
          files.stream()
              .sorted(
                  Comparator.comparing(
                      DiffFile::path,
                      WorkspaceDiffM4S03IntegrationTest::compareUnicodeCodePoints))
              .toList();
      StringBuilder canonical = new StringBuilder();
      for (DiffFile file : sorted) {
        canonical
            .append(file.path())
            .append('|')
            .append(file.oldPath() == null ? "" : file.oldPath())
            .append('|')
            .append(file.kind())
            .append('|')
            .append(file.additions())
            .append('|')
            .append(file.deletions())
            .append('|')
            .append(file.binary())
            .append('|')
            .append(file.patchTruncated())
            .append('|')
            .append(file.patchSha256())
            .append('\n');
      }
      return new DiffManifest(generation, List.copyOf(sorted), sha256(canonical.toString()));
    }
  }

  private record DiffStreamEvent(
      UUID workspaceId,
      UUID streamEpoch,
      long sequence,
      long generation,
      String cursor,
      EventKind kind,
      List<DiffFile> files,
      List<String> removals,
      String manifestHash) {}

  private record DiffCursor(UUID workspaceId, UUID streamEpoch, long sequence, long generation) {
    String payload() {
      return "1|" + workspaceId + "|" + streamEpoch + "|" + sequence + "|" + generation;
    }

    static DiffCursor parse(String payload) {
      String[] parts = payload.split("\\|", -1);
      if (parts.length != 5 || !"1".equals(parts[0])) {
        throw new CursorFailure("unsupported cursor payload");
      }
      long sequence = Long.parseLong(parts[3]);
      long generation = Long.parseLong(parts[4]);
      if (sequence < 1 || generation < 0) {
        throw new CursorFailure("cursor values outside range");
      }
      return new DiffCursor(
          UUID.fromString(parts[1]), UUID.fromString(parts[2]), sequence, generation);
    }
  }

  private record PatchArtifact(String content, String sha256) {}

  private record FinalDiffArtifact(
      UUID workspaceId,
      CommitId baselineCommit,
      CommitId deliveryCommit,
      DiffManifest manifest,
      PatchArtifact artifact,
      String finalHash) {
    static FinalDiffArtifact create(
        UUID workspaceId,
        CommitId baseline,
        CommitId delivery,
        DiffManifest manifest,
        PatchArtifact artifact) {
      String hash =
          sha256(
              workspaceId
                  + "|"
                  + baseline.value()
                  + "|"
                  + delivery.value()
                  + "|"
                  + manifest.manifestHash()
                  + "|"
                  + manifest.generation()
                  + "|"
                  + artifact.sha256());
      return new FinalDiffArtifact(workspaceId, baseline, delivery, manifest, artifact, hash);
    }

    String recomputeHash() {
      return sha256(
          workspaceId
              + "|"
              + baselineCommit.value()
              + "|"
              + deliveryCommit.value()
              + "|"
              + manifest.manifestHash()
              + "|"
              + manifest.generation()
              + "|"
              + artifact.sha256());
    }
  }

  private record LineStats(int additions, int deletions, boolean binary) {
    static LineStats from(String patch) {
      int additions = 0;
      int deletions = 0;
      for (String line : patch.split("\\R", -1)) {
        if (line.startsWith("+") && !line.startsWith("+++")) {
          additions++;
        }
        if (line.startsWith("-") && !line.startsWith("---")) {
          deletions++;
        }
      }
      boolean binary = patch.contains("GIT binary patch") || patch.contains("Binary files ");
      return new LineStats(additions, deletions, binary);
    }
  }

  private record PatchPreview(String content, boolean truncated) {
    static PatchPreview create(String patch, int byteLimit, int lineLimit) {
      StringBuilder preview = new StringBuilder();
      int bytes = 0;
      int lines = 0;
      for (String segment : patch.split("(?<=\\n)", -1)) {
        int segmentBytes = segment.getBytes(StandardCharsets.UTF_8).length;
        if (lines >= lineLimit || bytes + segmentBytes > byteLimit) {
          break;
        }
        preview.append(segment);
        bytes += segmentBytes;
        lines++;
      }
      return new PatchPreview(preview.toString(), preview.length() < patch.length());
    }
  }

  private enum ChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    TYPE_CHANGED;

    static ChangeKind from(char value) {
      return switch (value) {
        case 'A' -> ADDED;
        case 'M' -> MODIFIED;
        case 'D' -> DELETED;
        case 'R' -> RENAMED;
        case 'C' -> COPIED;
        case 'T' -> TYPE_CHANGED;
        default -> throw new ProtocolFailure("unsupported git change kind: " + value);
      };
    }
  }

  private enum EventKind {
    RESET,
    DELTA
  }

  private enum ApplyOutcome {
    APPLIED,
    RESET,
    DUPLICATE,
    GAP,
    STALE
  }

  private static Map<String, DiffFile> byPath(List<DiffFile> files) {
    Map<String, DiffFile> byPath = new LinkedHashMap<>();
    files.forEach(file -> byPath.put(file.path(), file));
    return byPath;
  }

  private static int compareUnicodeCodePoints(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftPoint = left.codePointAt(leftIndex);
      int rightPoint = right.codePointAt(rightIndex);
      if (leftPoint != rightPoint) {
        return Integer.compare(leftPoint, rightPoint);
      }
      leftIndex += Character.charCount(leftPoint);
      rightIndex += Character.charCount(rightPoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }

  private static String sha256(String content) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static class ProtocolFailure extends RuntimeException {
    private ProtocolFailure(String message) {
      super(message);
    }

    private ProtocolFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class CursorFailure extends ProtocolFailure {
    private CursorFailure(String message) {
      super(message);
    }

    private CursorFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
