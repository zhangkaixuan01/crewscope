package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint is the only filter identity a cursor token carries, so it must be a pure function
 * of the filter's value set — never of iteration order or set implementation (M9b-A06).
 */
class WorkItemFilterTest {

  @Test
  void fingerprintsAreStableAcrossSetOrderingsAndImplementations() {
    WorkItemFilter hashSet =
        new WorkItemFilter(
            Set.of(WorkItemStatus.BACKLOG),
            Set.of(WorkItemType.FEATURE, WorkItemType.BUG),
            Set.of(WorkItemPriority.URGENT),
            Optional.of(ResponsibilityRole.OWNER));
    LinkedHashSet<WorkItemType> typesInReverseOrder = new LinkedHashSet<>();
    typesInReverseOrder.add(WorkItemType.BUG);
    typesInReverseOrder.add(WorkItemType.FEATURE);
    WorkItemFilter linkedSet =
        new WorkItemFilter(
            new LinkedHashSet<>(Set.of(WorkItemStatus.BACKLOG)),
            typesInReverseOrder,
            new LinkedHashSet<>(Set.of(WorkItemPriority.URGENT)),
            Optional.of(ResponsibilityRole.OWNER));

    assertEquals(hashSet.fingerprint(), linkedSet.fingerprint());
    assertEquals(hashSet.fingerprint(), hashSet.fingerprint(), "repeated computation is stable");
    assertTrue(hashSet.fingerprint().value().matches("[0-9a-f]{64}"));
  }

  @Test
  void everyFilterDimensionChangesTheFingerprint() {
    WorkItemFilter base =
        new WorkItemFilter(
            Set.of(WorkItemStatus.BACKLOG),
            Set.of(WorkItemType.FEATURE),
            Set.of(WorkItemPriority.HIGH),
            Optional.of(ResponsibilityRole.OWNER));
    String baseFingerprint = base.fingerprint().value();

    assertNotEquals(baseFingerprint, withStatuses(base, Set.of(WorkItemStatus.READY)).value());
    assertNotEquals(
        baseFingerprint,
        withStatuses(base, Set.of(WorkItemStatus.BACKLOG, WorkItemStatus.IN_REVIEW)).value());
    assertNotEquals(baseFingerprint, withTypes(base, Set.of(WorkItemType.BUG)).value());
    assertNotEquals(baseFingerprint, withPriorities(base, Set.of(WorkItemPriority.LOW)).value());
    assertNotEquals(
        baseFingerprint, withRole(base, Optional.of(ResponsibilityRole.REVIEWER)).value());
    assertNotEquals(baseFingerprint, withRole(base, Optional.empty()).value());
  }

  @Test
  void allIsTheEmptyFilterAndOfStatusKeepsTheLegacyShape() {
    assertEquals(
        WorkItemFilter.ALL.fingerprint(),
        new WorkItemFilter(Set.of(), Set.of(), Set.of(), Optional.empty()).fingerprint());
    assertEquals(
        Set.of(WorkItemStatus.BACKLOG), WorkItemFilter.ofStatus(WorkItemStatus.BACKLOG).statuses());
    assertTrue(WorkItemFilter.ofStatus(WorkItemStatus.BACKLOG).types().isEmpty());
    assertTrue(WorkItemFilter.ofStatus(WorkItemStatus.BACKLOG).priorities().isEmpty());
  }

  private static WorkItemFilterFingerprint withStatuses(
      WorkItemFilter base, Set<WorkItemStatus> statuses) {
    return new WorkItemFilter(statuses, base.types(), base.priorities(), base.responsibilityRole())
        .fingerprint();
  }

  private static WorkItemFilterFingerprint withTypes(WorkItemFilter base, Set<WorkItemType> types) {
    return new WorkItemFilter(base.statuses(), types, base.priorities(), base.responsibilityRole())
        .fingerprint();
  }

  private static WorkItemFilterFingerprint withPriorities(
      WorkItemFilter base, Set<WorkItemPriority> priorities) {
    return new WorkItemFilter(base.statuses(), base.types(), priorities, base.responsibilityRole())
        .fingerprint();
  }

  private static WorkItemFilterFingerprint withRole(
      WorkItemFilter base, Optional<ResponsibilityRole> role) {
    return new WorkItemFilter(base.statuses(), base.types(), base.priorities(), role).fingerprint();
  }
}
