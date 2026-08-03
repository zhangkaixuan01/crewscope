package io.crewscope.domain.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorkItemTest {

    @Test
    void followsTheDeliveryStateMachine() {
        WorkItem workItem = WorkItem.create(
                        WorkItemId.generate(), new WorkItemKey("CRW-1024"), "Initialize CrewScope")
                .transitionTo(WorkItemStatus.READY)
                .transitionTo(WorkItemStatus.IN_PROGRESS)
                .transitionTo(WorkItemStatus.IN_REVIEW)
                .transitionTo(WorkItemStatus.DONE);

        assertEquals(WorkItemStatus.DONE, workItem.status());
        assertEquals(4, workItem.version());
    }

    @Test
    void rejectsInvalidTransition() {
        WorkItem workItem = WorkItem.create(
                WorkItemId.generate(), new WorkItemKey("CRW-1025"), "Invalid transition");

        assertThrows(IllegalStateException.class, () -> workItem.transitionTo(WorkItemStatus.DONE));
    }
}
