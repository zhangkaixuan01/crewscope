package io.crewscope.application.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.workspace.AgentProfileId;
import org.junit.jupiter.api.Test;

class TaskAgentStateIdentityM4I12Test {

    @Test
    void taskAndCodingRolesUseDifferentStableSnapshotNamespaces() {
        AgentProfileId profileId = AgentProfileId.from(
                "11111111-1111-4111-8111-111111111111");

        String task = TaskAgentStateIdentity.stableAgentId(
                profileId, 7, TaskAgentSessionPurpose.TASK);
        String step = TaskAgentStateIdentity.stableAgentId(
                profileId, 7, TaskAgentSessionPurpose.STEP);
        String specialist = TaskAgentStateIdentity.stableAgentId(
                profileId, 7, TaskAgentSessionPurpose.SPECIALIST);

        assertEquals("crewscope-task-" + profileId + "-v7", task);
        assertEquals(task, step);
        assertEquals("crewscope-coding-" + profileId + "-v7", specialist);
        assertNotEquals(task, specialist);
    }
}
