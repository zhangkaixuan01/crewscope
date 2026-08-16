package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryPathPolicyJudgeTest {

  @TempDir Path temporaryDirectory;

  @Test
  void acceptsAnExistingFileInsideTheCanonicalRoot() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("allowed"));
    Path source = Files.writeString(root.resolve("Source.java"), "class Source {}\n");
    assertEquals(source.toRealPath(), new RepositoryPathPolicy(root).resolve("Source.java"));
  }

  @Test
  void rejectsLexicalTraversalAbsolutePathsAndSymlinkEscapes() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("allowed"));
    Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(root.resolve("link"), outside);
    RepositoryPathPolicy policy = new RepositoryPathPolicy(root);

    assertThrows(IllegalArgumentException.class, () -> policy.resolve("../outside/secret.txt"));
    assertThrows(IllegalArgumentException.class, () -> policy.resolve(outside.resolve("secret.txt").toString()));
    assertThrows(IllegalArgumentException.class, () -> policy.resolve("link/secret.txt"));
  }
}
