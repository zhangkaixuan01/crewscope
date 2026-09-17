package io.crewscope.application.workitem;

import static io.crewscope.application.workitem.WorkItemCommandTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the availability projection and the command that executes a transition answer alike.
 *
 * <p>M9 forbids the projection and the command from each deciding availability for themselves, so
 * this test reads the projection as a promise and the command as the authority: on every cell of the
 * (status × native/external × permission) matrix it submits each projected edge for real. If the two
 * ever disagree, one of them is lying to a member — either offering an action the server then
 * rejects, or withholding one that would have worked.
 */
class WorkItemTransitionAvailabilityReconciliationTest {

  private static final WorkItemTransitionAvailabilityProjector PROJECTOR =
      new WorkItemTransitionAvailabilityProjector();

  /** The action string {@code WorkItemCommandService} names when it applies the permission rule. */
  private static final String PERMISSION_REJECTION = "transition WorkItems in this WorkProject";

  /** The action string it names when it refuses an item whose source it does not own. */
  private static final String EXTERNAL_REJECTION = "transition an externally managed WorkItem";

  @Test
  void acceptsEveryEdgeTheProjectionOffersOnTheWholeMatrix() {
    int offered = 0;

    for (WorkItemStatus status : WorkItemStatus.values()) {
      for (boolean nativeSource : new boolean[] {true, false}) {
        for (boolean participates : new boolean[] {true, false}) {
          String where = where(status, nativeSource, participates);
          List<WorkItemAvailableTransition> projected =
              PROJECTOR.all(status, nativeSource, participates);
          List<WorkItemAvailableTransition> offeredEdges =
              projected.stream().filter(WorkItemAvailableTransition::enabled).toList();

          // The projection offers exactly the state machine's edges, and only when the member may
          // participate on a natively owned item. Any other number would be a decision of its own
          // rather than a restatement of the two facts it was handed.
          assertEquals(
              nativeSource && participates ? projected.size() : 0,
              offeredEdges.size(),
              "the projection offers the wrong number of edges at " + where);

          for (WorkItemAvailableTransition edge : offeredEdges) {
            CommandExecution<WorkItem> execution =
                Probe.of(status, nativeSource, participates).transition(edge.targetStatus());

            assertEquals(
                edge.targetStatus(),
                execution.result().orElseThrow().status(),
                "the command must reach the offered target at " + where + " for " + edge.actionId());
            offered++;
          }
        }
      }
    }

    assertTrue(offered > 0, "the matrix must contain at least one offered edge");
  }

  @Test
  void rejectsEveryEdgeTheProjectionWithholdsAndForTheReasonItNamed() {
    int withheld = 0;

    for (WorkItemStatus status : WorkItemStatus.values()) {
      for (boolean nativeSource : new boolean[] {true, false}) {
        for (boolean participates : new boolean[] {true, false}) {
          String where = where(status, nativeSource, participates);
          List<WorkItemAvailableTransition> projected =
              PROJECTOR.all(status, nativeSource, participates);
          List<WorkItemAvailableTransition> withheldEdges =
              projected.stream().filter(value -> !value.enabled()).toList();

          assertEquals(
              nativeSource && participates ? 0 : projected.size(),
              withheldEdges.size(),
              "the projection withholds the wrong number of edges at " + where);

          for (WorkItemAvailableTransition edge : withheldEdges) {
            Probe probe = Probe.of(status, nativeSource, participates);

            PolicyDeniedException failure =
                assertThrows(
                    PolicyDeniedException.class,
                    () -> probe.transition(edge.targetStatus()),
                    "the command must reject the withheld edge at "
                        + where
                        + " for "
                        + edge.actionId());

            assertEquals(
                namedCause(edge, participates),
                failure.error().details().get("action"),
                "the rejection must name the same cause the projection gave at " + where);
            withheld++;
          }
        }
      }
    }

    assertTrue(withheld > 0, "the matrix must contain at least one withheld edge");
  }

  /**
   * The cause the command must name when it rejects a withheld edge.
   *
   * <p>The command applies the {@code WORK_PARTICIPATE} rule before it checks the item's source, so a
   * member who may not participate is rejected for the permission even on an externally managed item.
   * The projection calls that cell {@code EXTERNAL_PROVIDER_MANAGED} and is still right — the only
   * claim a disabled edge makes is that execution would fail — but only the permission reason names
   * the rule the command reaches first.
   *
   * <p>Reaching the default arm means a reason no rule produces. Failing there is the point: the
   * frozen reasons must stay unreachable until a milestone gives them rules, and this arm is where
   * that stays true out loud.
   */
  private static String namedCause(WorkItemAvailableTransition edge, boolean participates) {
    return switch (edge.reason().orElseThrow()) {
      case EXTERNAL_PROVIDER_MANAGED -> participates ? EXTERNAL_REJECTION : PERMISSION_REJECTION;
      case PERMISSION_DENIED -> PERMISSION_REJECTION;
      default ->
          throw new AssertionError(
              "no rule produces "
                  + edge.reason().orElseThrow()
                  + ", so "
                  + edge.actionId()
                  + " cannot have been withheld");
    };
  }

  private static String where(WorkItemStatus status, boolean nativeSource, boolean participates) {
    return status
        + (nativeSource ? "/native" : "/external")
        + (participates ? "/may participate" : "/may not participate");
  }

  /**
   * One matrix cell, built so the projection and the command are handed the same facts.
   *
   * <p>Every field the projection reads comes from the item the command will load, and the command
   * executes through the one {@link WorkItemAccessPolicy} the test also projects through, so a
   * disagreement cannot come from the two sides being told different stories.
   */
  private record Probe(WorkItemCommandTestSupport.Fixture fixture, WorkItem item) {

    static Probe of(WorkItemStatus status, boolean nativeSource, boolean participates) {
      WorkItemCommandTestSupport.Fixture fixture = new WorkItemCommandTestSupport.Fixture();
      // The default owner grant is Team-scoped and satisfies every project, so "may not
      // participate" is modelled by scoping the member's only role to a different project.
      fixture.useProjectRole(participates ? fixture.project.id() : WorkProjectId.generate());
      return new Probe(fixture, fixture.store.create(itemAt(fixture, status, nativeSource)));
    }

    CommandExecution<WorkItem> transition(WorkItemStatus target) {
      long version =
          fixture.store.findById(fixture.organizationId, item.id()).orElseThrow().version();
      return fixture
          .serviceSharingAccessPolicy()
          .transition(
              fixture.context("reconcile-" + target),
              fixture.initialization.team().id(),
              fixture.project.id(),
              item.id(),
              new TransitionWorkItemCommand(target, version));
    }
  }

  /**
   * Builds the item by walking the state machine, so every status is a state the domain itself
   * considers reachable rather than one reconstituted behind its back.
   */
  private static WorkItem itemAt(
      WorkItemCommandTestSupport.Fixture fixture, WorkItemStatus status, boolean nativeSource) {
    WorkItem item =
        nativeSource
            ? WorkItem.createNative(
                WorkItemId.generate(),
                fixture.project,
                new WorkItemKey("CRW-1"),
                WorkItemType.FEATURE,
                "Reconciled",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                fixture.actor,
                NOW)
            : WorkItem.createExternalProjection(
                WorkItemId.generate(),
                fixture.project,
                new WorkItemKey("CRW-2"),
                WorkItemType.FEATURE,
                "Reconciled",
                Optional.empty(),
                WorkItemPriority.MEDIUM,
                Set.of(),
                Optional.empty(),
                WorkItemSource.JIRA,
                "JIRA-1",
                fixture.actor,
                NOW);

    for (WorkItemStatus step : pathTo(status)) {
      item = item.transitionTo(step, fixture.actor.id(), NOW);
    }
    return item;
  }

  /** The shortest edge sequence from the initial status to the requested one. */
  private static List<WorkItemStatus> pathTo(WorkItemStatus target) {
    Map<WorkItemStatus, WorkItemStatus> cameFrom = new LinkedHashMap<>();
    Deque<WorkItemStatus> pending = new ArrayDeque<>();
    cameFrom.put(WorkItemStatus.BACKLOG, null);
    pending.addLast(WorkItemStatus.BACKLOG);

    while (!pending.isEmpty()) {
      WorkItemStatus current = pending.removeFirst();
      if (current == target) {
        break;
      }
      for (WorkItemStatus next : WorkItem.allowedTransitionsFrom(current)) {
        if (cameFrom.putIfAbsent(next, current) == null) {
          pending.addLast(next);
        }
      }
    }

    assertTrue(cameFrom.containsKey(target), "the state machine must be able to reach " + target);
    LinkedList<WorkItemStatus> path = new LinkedList<>();
    for (WorkItemStatus step = target; step != WorkItemStatus.BACKLOG; step = cameFrom.get(step)) {
      path.addFirst(step);
    }
    return path;
  }
}
