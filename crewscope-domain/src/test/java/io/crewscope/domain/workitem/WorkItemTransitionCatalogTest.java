package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves action metadata never introduces or removes a domain state-machine edge. */
class WorkItemTransitionCatalogTest {
  @Test
  void catalogEdgesMatchTheAuthoritativeWorkItemStateMachine() {
    for (WorkItemStatus status : WorkItemStatus.values()) {
      assertEquals(
          WorkItem.allowedTransitionsFrom(status),
          WorkItemTransitionCatalog.from(status).stream()
              .map(WorkItemTransitionCatalog.Edge::to)
              .collect(java.util.stream.Collectors.toSet()),
          "catalog mismatch for " + status);
    }
    assertEquals(17, Arrays.stream(WorkItemStatus.values())
        .mapToInt(status -> WorkItemTransitionCatalog.from(status).size()).sum());
  }

  @Test
  void reversibleEdgesAlwaysHaveAnExecutableReverseEdge() {
    for (WorkItemStatus status : WorkItemStatus.values()) {
      for (WorkItemTransitionCatalog.Edge edge : WorkItemTransitionCatalog.from(status)) {
        if (!edge.metadata().reversible()) continue;
        assertTrue(
            WorkItem.allowedTransitionsFrom(edge.to()).contains(edge.from()),
            "reversible edge without a reverse edge: " + edge.from() + " -> " + edge.to());
        Optional<WorkItemTransitionCatalog.Edge> reverse =
            WorkItemTransitionCatalog.reverseOf(edge.from(), edge.to());
        assertTrue(reverse.isPresent(), "missing reverse metadata for " + edge.from());
        assertEquals(edge.to(), reverse.get().from());
        assertEquals(edge.from(), reverse.get().to());
      }
    }
  }

  @Test
  void declaredIntentIsNarrowedWhenTheStateMachineHasNoReverseEdge() {
    // BACKLOG -> READY reads as cheap to reverse, but READY has no edge back to BACKLOG.
    assertFalse(reversible(WorkItemStatus.BACKLOG, WorkItemStatus.READY));
    assertTrue(WorkItemTransitionCatalog.reverseOf(
        WorkItemStatus.BACKLOG, WorkItemStatus.READY).isEmpty());
    // IN_PROGRESS -> IN_REVIEW is reversible by "resume-work", which really exists.
    assertTrue(reversible(WorkItemStatus.IN_PROGRESS, WorkItemStatus.IN_REVIEW));
    assertEquals("resume-work", WorkItemTransitionCatalog
        .reverseOf(WorkItemStatus.IN_PROGRESS, WorkItemStatus.IN_REVIEW)
        .orElseThrow().metadata().actionId());
  }

  @Test
  void irreversibleOutcomesNeverOfferUndo() {
    assertFalse(reversible(WorkItemStatus.IN_REVIEW, WorkItemStatus.CANCELLED));
    assertFalse(reversible(WorkItemStatus.DONE, WorkItemStatus.ARCHIVED));
    assertFalse(reversible(WorkItemStatus.CANCELLED, WorkItemStatus.ARCHIVED));
    assertFalse(reversible(WorkItemStatus.IN_REVIEW, WorkItemStatus.DONE));
  }

  private static boolean reversible(WorkItemStatus from, WorkItemStatus to) {
    return WorkItemTransitionCatalog.from(from).stream()
        .filter(edge -> edge.to() == to)
        .findFirst()
        .orElseThrow()
        .metadata()
        .reversible();
  }
}
