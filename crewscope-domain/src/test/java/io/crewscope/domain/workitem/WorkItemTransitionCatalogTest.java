package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
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
}
