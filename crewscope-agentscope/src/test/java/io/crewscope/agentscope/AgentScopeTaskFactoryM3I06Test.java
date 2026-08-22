package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.Task;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.task.AgentScopeTaskPlanAdapter;
import io.crewscope.agentscope.task.AgentScopeTaskPlanningSnapshotMapper;
import io.crewscope.agentscope.task.ControlledTaskPlanParser;
import io.crewscope.agentscope.task.ControlledTaskPlanValidationTool;
import io.crewscope.agentscope.task.ControlledTaskToolkitFactory;
import io.crewscope.agentscope.task.TaskAgentConfiguration;
import io.crewscope.agentscope.task.TaskAgentFactory;
import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TodoStatus;
import io.crewscope.domain.workspace.AgentProfileId;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/** M3-I06 strict plan language and version-pinned Task Agent Factory tests. */
class AgentScopeTaskFactoryM3I06Test {

    private static final String VALID_PLAN =
            """
            # Controlled Task Plan

            - `inspect` | ANALYSIS | Inspect input | deps=- | capabilities=PLAN | tools=fixture_inspect | critical=true
            - `execute` | IMPLEMENTATION | Produce result | deps=inspect | capabilities=PLAN | tools=fixture_execute | critical=true
            - `validate` | VALIDATION | Validate result | deps=execute | capabilities=STRUCTURED_OUTPUT | tools=fixture_validate | critical=true
            """;

    @TempDir Path runtimeRoot;

    @Test
    void parsesCorrectedPlanAndKeepsTodoAsAnUnpublishedProjection() {
        ControlledTaskPlanParser parser = new ControlledTaskPlanParser();
        String invalid = VALID_PLAN.replace("deps=execute", "deps=missing");
        assertFalse(parser.validate(invalid).valid());
        assertTrue(parser.validate(VALID_PLAN).valid());

        AgentState state = AgentState.builder().build();
        state.getPlanModeContext().setCurrentPlanFile("plans/PLAN.md");
        state.getTasksContext().tasksMutable().addAll(List.of(
                todo("[inspect] Inspect input", Task.State.COMPLETED, "high"),
                todo("[execute] Produce result", Task.State.IN_PROGRESS, "medium"),
                todo("Unbound runtime note", Task.State.PENDING, null)));
        AgentScopeTaskPlanningSnapshotMapper.TaskPlanningSnapshot snapshot =
                new AgentScopeTaskPlanningSnapshotMapper().map(state, Optional.of(VALID_PLAN));

        AgentScopeTaskPlanAdapter.Candidate candidate =
                new AgentScopeTaskPlanAdapter(parser).adapt(snapshot);

        assertEquals(3, candidate.plan().steps().size());
        assertEquals(TodoStatus.IN_PROGRESS, candidate.todos().get(1).status());
        assertEquals(Optional.of("execute"), candidate.todos().get(1).planStepKey());
        assertEquals(Optional.empty(), candidate.todos().get(2).planStepKey());
        // Mapping and Todo cognition do not create or mutate a CrewScope StepExecution.
        assertEquals(3, state.getTasksContext().getTasks().size());
    }

    @Test
    void fixesIdentityAndToolkitToThePinnedProfileVersion() {
        AgentProfileId profileId = AgentProfileId.generate();
        ScriptedModel model = new ScriptedModel("unused");
        ControlledTaskPlanParser parser = new ControlledTaskPlanParser();
        try (TaskAgentFactory factory = new TaskAgentFactory(
                (id, version) -> configuration(id, version),
                ignored -> model,
                new InMemoryAgentStateStore(),
                new ControlledTaskToolkitFactory(parser),
                runtimeRoot)) {
            HarnessAgent v1 = factory.getOrCreate(
                    session(profileId, 1), policy(profileId, 1));
            HarnessAgent sameV1 = factory.getOrCreate(
                    session(profileId, 1), policy(profileId, 1));
            HarnessAgent v2 = factory.getOrCreate(
                    session(profileId, 2), policy(profileId, 2));

            assertSame(v1, sameV1);
            assertNotSame(v1, v2);
            assertEquals(2, factory.cachedAgentCount());
            assertTrue(v1.getName().endsWith("-v1"));
            assertTrue(v2.getName().endsWith("-v2"));
            Set<String> expected = new HashSet<>(TaskPlanPublicationService.M3_CONTROLLED_TOOLS);
            expected.addAll(Set.of(
                    ControlledTaskPlanValidationTool.NAME,
                    "plan_enter",
                    "plan_write",
                    "plan_exit",
                    "todo_write"));
            assertEquals(expected, v1.getToolkit().getToolNames());
            assertTrue(v1.getToolkit().getTool(ControlledTaskPlanValidationTool.NAME).isReadOnly());
            assertFalse(v1.getToolkit().getTool("fixture_execute").isReadOnly());
            assertTrue(v1.getToolkit().getToolNames().stream()
                    .allMatch(name -> name.matches("[a-zA-Z0-9_-]+")));
            assertFalse(v1.getToolkit().getToolNames().stream()
                    .anyMatch(name -> name.startsWith("github.") || name.startsWith("provider.")));
        }
    }

    @Test
    void failsClosedForProfileMismatchAndInjectedProviderWriteTool() {
        AgentProfileId profileId = AgentProfileId.generate();
        ControlledTaskPlanParser parser = new ControlledTaskPlanParser();
        try (TaskAgentFactory factory = new TaskAgentFactory(
                (id, version) -> configuration(id, version),
                ignored -> new ScriptedModel("unused"),
                new InMemoryAgentStateStore(),
                () -> {
                    Toolkit toolkit = new ControlledTaskToolkitFactory(parser).get();
                    toolkit.registerAgentTool(new ProviderWriteTool());
                    return toolkit;
                },
                runtimeRoot)) {
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(
                    session(profileId, 1), policy(profileId, 1)));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(
                    session(profileId, 1), policy(profileId, 2)));
        }
    }

    private static Task todo(String content, Task.State status, String priority) {
        return Task.builder()
                .subject(content)
                .description(content)
                .state(status)
                .metadata(priority == null ? null : Map.of("priority", priority))
                .build();
    }

    private static TaskAgentRuntimeSession session(AgentProfileId profileId, long version) {
        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        when(session.agentProfileId()).thenReturn(profileId);
        when(session.agentProfileVersion()).thenReturn(version);
        return session;
    }

    private static PolicySnapshot policy(AgentProfileId profileId, long version) {
        PolicySnapshot policy = mock(PolicySnapshot.class);
        when(policy.agentProfileId()).thenReturn(profileId);
        when(policy.agentProfileVersion()).thenReturn(version);
        return policy;
    }

    private static TaskAgentConfiguration configuration(
            AgentProfileId profileId, long version) {
        return new TaskAgentConfiguration(
                profileId,
                version,
                "scripted",
                Optional.empty(),
                "Use controlled Task plans and Fixture Tools only.",
                20,
                1);
    }

    private static final class ProviderWriteTool extends ToolBase {

        private ProviderWriteTool() {
            super(ToolBase.builder()
                    .name("github.write")
                    .description("Forbidden Provider write")
                    .inputSchema(Map.of("type", "object", "properties", Map.of()))
                    .readOnly(false));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("written"));
        }
    }
}
