package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.activity.ActivityCategory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Filter normalization, bounded cardinality and resume-coordinate contract for M6-A01. */
class ActivityApiSupportM6A01Test {

  @Test
  void normalizesRepeatedAndCommaSeparatedFilters() {
    var filter = ActivityApiSupport.teamFilter(
        null,
        List.of("task,WORK_ITEM", "TASK"),
        List.of("WORK_ITEM_CREATED", "WORK_ITEM_CREATED"),
        List.of());

    assertEquals(Set.of(ActivityCategory.TASK, ActivityCategory.WORK_ITEM), filter.categories());
    assertEquals(1, filter.eventTypes().size());
  }

  @Test
  void rejectsMoreThanTwentyDistinctFilterValuesAndConflictingResumeTokens() {
    List<String> values = java.util.stream.IntStream.range(0, 21)
        .mapToObj(index -> "EVENT_" + index)
        .toList();

    assertThrows(
        ApiRequestException.class,
        () -> ActivityApiSupport.teamFilter(null, List.of(), values, List.of()));
    ApiRequestException conflict = assertThrows(
        ApiRequestException.class,
        () -> ActivityApiSupport.resumeToken("header", "parameter"));
    assertEquals("invalid_cursor", conflict.code());
  }
}
