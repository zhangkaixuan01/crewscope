package io.crewscope.domain.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectExecutionDefaultsTest {

    private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
    private static final TeamId TEAM = new TeamId(UUID.randomUUID());
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final WorkProjectId PROJECT = new WorkProjectId(UUID.randomUUID());

    @Test
    void emptyDefaultsUseVersionZeroAndNextBumpsOnlyTheVersion() {
        ProjectExecutionDefaults empty = ProjectExecutionDefaults.empty(ORGANIZATION, TEAM, WORKSPACE, PROJECT);
        ProjectExecutionDefaults next = empty.next(
                Optional.of(new RepositoryBindingId(UUID.randomUUID())), Optional.of(2L),
                Optional.of(new RepositoryBranchName("main")),
                Optional.of(new BuildProfileReference("maven-java-17", 1, TaskFactHash.sha256("profile"))),
                Optional.empty(), Optional.empty());

        assertEquals(0, empty.version());
        assertEquals(1, next.version());
        assertEquals("main", next.branch().orElseThrow().value());
    }

    @Test
    void rejectsUnpairedBindingAndAgentRevision() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectExecutionDefaults(
                ORGANIZATION, TEAM, WORKSPACE, PROJECT, 0,
                Optional.of(RepositoryBindingId.generate()), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectExecutionDefaults(
                ORGANIZATION, TEAM, WORKSPACE, PROJECT, 0,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(1L)));
    }
}
