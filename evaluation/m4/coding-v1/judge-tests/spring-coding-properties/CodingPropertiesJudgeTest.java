package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodingPropertiesJudgeTest {

  @Test
  void acceptsAClosedNetworkAndNormalizedRelativePaths() {
    CodingProperties properties = new CodingProperties(false, List.of("src/main/java"), 120);
    assertEquals(List.of("src/main/java"), properties.allowedPaths());
  }

  @Test
  void rejectsUnsafeConfigurationAndDefensivelyCopiesPaths() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CodingProperties(true, List.of("src/main/java"), 120));
    assertThrows(
        IllegalArgumentException.class, () -> new CodingProperties(false, List.of("/"), 120));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CodingProperties(false, List.of("../outside"), 120));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CodingProperties(false, List.of("src/main/java"), 0));

    ArrayList<String> paths = new ArrayList<>(List.of("src/main/java"));
    CodingProperties properties = new CodingProperties(false, paths, 120);
    paths.add("outside");
    assertEquals(List.of("src/main/java"), properties.allowedPaths());
  }
}
