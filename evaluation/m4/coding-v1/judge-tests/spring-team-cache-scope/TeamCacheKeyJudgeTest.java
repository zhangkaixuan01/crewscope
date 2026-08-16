package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TeamCacheKeyJudgeTest {

  @Test
  void includesEveryTenantAndResourceCoordinate() {
    TeamCacheKey keys = new TeamCacheKey();
    String first = keys.forWorkItem("org-a", "team-a", "work-1");
    String second = keys.forWorkItem("org-a", "team-b", "work-1");
    assertNotEquals(first, second);
    assertEquals("org-a:team-a:work-1", first);
  }

  @Test
  void rejectsBlankCoordinates() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> new TeamCacheKey().forWorkItem("org-a", " ", "work-1"));
  }
}
