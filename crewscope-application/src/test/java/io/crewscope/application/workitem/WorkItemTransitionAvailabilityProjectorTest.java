package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemTransitionCatalog;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves the WorkDesk's short list and the detail endpoint's full list come from one verdict.
 *
 * <p>M9 forbids a list view and a detail view from each deciding availability for themselves, so the
 * two must be related by filtering and nothing else — if they ever diverge, a member would be shown
 * an action on one surface that the other says is impossible (or worse, that the command rejects).
 */
class WorkItemTransitionAvailabilityProjectorTest {

  private final WorkItemTransitionAvailabilityProjector projector =
      new WorkItemTransitionAvailabilityProjector();

  @Test
  void keepsTheWorkDeskListEqualToTheExecutableSubsetOfTheEndpointList() {
    for (WorkItemStatus status : WorkItemStatus.values()) {
      for (boolean nativeSource : new boolean[] {true, false}) {
        for (boolean participates : new boolean[] {true, false}) {
          String where = status + "/native=" + nativeSource + "/participates=" + participates;
          List<WorkItemAvailableTransition> all = projector.all(status, nativeSource, participates);
          assertEquals(
              all.stream().filter(WorkItemAvailableTransition::enabled).toList(),
              projector.enabled(status, nativeSource, participates),
              "the WorkDesk list must be this exact filter at " + where);
        }
      }
    }
  }

  @Test
  void marksEveryDisabledEdgeWithAReasonSoNoSurfaceHasToInventOne() {
    for (WorkItemStatus status : WorkItemStatus.values()) {
      for (WorkItemAvailableTransition value :
          projector.all(status, false, false)) {
        assertFalse(value.enabled());
        assertTrue(value.reason().isPresent(), "a disabled edge needs a reason: " + value.actionId());
      }
    }
  }

  @Test
  void projectsExactlyTheDomainEdgesSoNoSurfaceCanWidenThem() {
    for (WorkItemStatus status : WorkItemStatus.values()) {
      assertEquals(
          WorkItemTransitionCatalog.from(status).stream()
              .map(WorkItemTransitionCatalog.Edge::to)
              .toList(),
          projector.all(status, true, true).stream().map(WorkItemAvailableTransition::targetStatus).toList(),
          "projected targets must equal the state machine's edges from " + status);
    }
  }

  @Test
  void treatsAnArchivedItemAsUnavailableEvenThoughItCurrentlyHasNoEdgesToDisable() {
    // The projection is empty because ARCHIVED is terminal, not because archival was adjudicated.
    // Keeping the archival check means an archived item that later gained an edge still could not be
    // offered as executable.
    assertTrue(WorkItemTransitionCatalog.from(WorkItemStatus.ARCHIVED).isEmpty());
    assertTrue(projector.all(WorkItemStatus.ARCHIVED, true, true).isEmpty());
  }

  @Test
  void prefersNoConclusionToAWidenedOneForAnExternallyManagedItem() {
    List<WorkItemAvailableTransition> result = projector.all(WorkItemStatus.IN_PROGRESS, false, true);

    assertFalse(result.isEmpty(), "an external item still exposes its edges, all disabled");
    assertTrue(result.stream().allMatch(value ->
        !value.enabled()
            && value.reason().equals(Optional.of(TransitionBlockReason.EXTERNAL_PROVIDER_MANAGED))));
    assertTrue(projector.enabled(WorkItemStatus.IN_PROGRESS, false, true).isEmpty());
  }
}
