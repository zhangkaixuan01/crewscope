package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The terminal set drives the WorkGraph "currently blocked" projection (ADR-033). */
class WorkItemStatusTest {

    @Test
    void doneCancelledAndArchivedEndExecution() {
        assertTrue(WorkItemStatus.DONE.isTerminal());
        assertTrue(WorkItemStatus.CANCELLED.isTerminal());
        assertTrue(WorkItemStatus.ARCHIVED.isTerminal());
    }

    @Test
    void everyExecutingStatusStillBlocks() {
        Set.of(
                WorkItemStatus.BACKLOG,
                WorkItemStatus.READY,
                WorkItemStatus.IN_PROGRESS,
                WorkItemStatus.IN_REVIEW,
                WorkItemStatus.BLOCKED)
            .forEach(status -> assertFalse(status.isTerminal(), status::name));
    }
}
