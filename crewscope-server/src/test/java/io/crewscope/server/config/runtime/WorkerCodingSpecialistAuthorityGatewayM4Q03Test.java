package io.crewscope.server.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceFingerprint;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerCodingSpecialistAuthorityGatewayM4Q03Test {

    @Test
    void specialistInstructionCarriesFrozenTaskBriefAndAllowedPaths() {
        TaskExecutionRuntimeFacts facts = mock(TaskExecutionRuntimeFacts.class);
        Task task = mock(Task.class);
        CodingWorkspaceExecution execution = mock(CodingWorkspaceExecution.class);
        CodingTargetSnapshot target = mock(CodingTargetSnapshot.class);
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        when(facts.task()).thenReturn(task);
        when(task.brief()).thenReturn(new TaskBrief(
                "Normalize Unicode user names",
                List.of("Reject blank input", "Use Locale.ROOT")));
        when(execution.target()).thenReturn(target);
        when(execution.workspace()).thenReturn(workspace);
        when(target.id()).thenReturn(CodingTargetSnapshotId.generate());
        when(target.revision()).thenReturn(1L);
        when(target.allowedPaths()).thenReturn(new CodingTargetAllowedPaths(
                List.of("src/main/java/io/crewscope/evaluation/UserNameNormalizer.java")));
        when(target.snapshotHash()).thenReturn(
                io.crewscope.domain.task.TaskFactHash.sha256("target"));
        when(workspace.id()).thenReturn(io.crewscope.domain.coding.ExecutionWorkspaceId.generate());
        when(workspace.fingerprint()).thenReturn(
                new ExecutionWorkspaceFingerprint(
                        io.crewscope.domain.task.TaskFactHash.sha256("workspace").value()));

        String instruction = WorkerCodingSpecialistAuthorityGateway.instruction(
                facts, execution, 1, Optional.empty());

        assertThat(instruction)
                .contains("Task objective: Normalize Unicode user names")
                .contains("Acceptance criteria: Reject blank input; Use Locale.ROOT")
                .contains("Allowed repository paths: src/main/java/io/crewscope/evaluation/UserNameNormalizer.java")
                .contains("platform policy and registered tools remain the only execution authority");
    }
}
