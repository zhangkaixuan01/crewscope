package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.coding.AgentScopeCodingRuntime;
import io.crewscope.agentscope.coding.CodingSpecialistConfiguration;
import io.crewscope.agentscope.coding.CodingSpecialistConfigurationSource;
import io.crewscope.agentscope.coding.CodingSpecialistFactory;
import io.crewscope.agentscope.coding.CodingSpecialistRequest;
import io.crewscope.agentscope.coding.CodingSpecialistRunResult;
import io.crewscope.agentscope.coding.CodingSpecialistSkillBundle;
import io.crewscope.agentscope.coding.CodingSpecialistToolSurface;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M10-A03b Factory dynamic wiring: with resolved dynamic Team Skills the pinned and
 * resolved compositions keep the exact controlled tool surface, compaction and bundle
 * pin, the SkillLoadTool serves the dynamic repository's content, and an empty list is
 * the byte-stable pre-A03b shape.
 */
class CodingSpecialistFactoryDynamicSkillTest {

    private static final String DYNAMIC_KEY = "release-review-checklist";
    private static final int DYNAMIC_REVISION = 3;
    private static final String DYNAMIC_BODY = "Drain the pool first, then verify health.";

    @TempDir
    Path runtimeRoot;

    // ------------------------------------------------------------------ composition invariants

    @Test
    void dynamicSkillsKeepTheExactControlledSurfaceAndCompactionOnThePinnedPath() {
        ScriptedModel pinned = repeatedModel("unused pinned", 2);
        CodingSpecialistFactory factory = factory(pinned);

        try (HarnessAgent agent = factory.createPinned(
                specialistSession(),
                toolkit(new ControlledCodingTools()),
                new io.crewscope.agentscope.model.ResolvedAgentScopeModels(
                        pinned, Optional.empty()),
                List.of(dynamicSkill()))) {

            assertSame(pinned, agent.getModel());
            assertNotNull(agent.getCompactionHook());
            // Nine hard-disables plus the exact controlled surface survive the dynamic
            // repository join: a skill body can never widen the tool surface. AgentScope
            // installs the read-only Skill loader only at invocation time (M4-I11 shape).
            assertEquals(controlledSurfaceWithoutSkillLoader(),
                    agent.getToolkit().getToolNames());
        }
    }

    @Test
    void anEmptyDynamicListMatchesTheThreeArgumentPinnedShape() {
        ScriptedModel pinned = repeatedModel("unused pinned", 2);
        CodingSpecialistFactory factory = factory(pinned);

        try (HarnessAgent legacy = factory.createPinned(
                        specialistSession(),
                        toolkit(new ControlledCodingTools()),
                        new io.crewscope.agentscope.model.ResolvedAgentScopeModels(
                                pinned, Optional.empty()));
                HarnessAgent explicit = factory.createPinned(
                        specialistSession(),
                        toolkit(new ControlledCodingTools()),
                        new io.crewscope.agentscope.model.ResolvedAgentScopeModels(
                                pinned, Optional.empty()),
                        List.of())) {

            assertEquals(legacy.getToolkit().getToolNames(),
                    explicit.getToolkit().getToolNames());
            assertEquals(legacy.getMaxIters(), explicit.getMaxIters());
            assertEquals(
                    legacy.getDelegate().getSysPrompt(),
                    explicit.getDelegate().getSysPrompt());
            assertEquals(
                    legacy.getDelegate().getGenerateOptions().getMaxTokens(),
                    explicit.getDelegate().getGenerateOptions().getMaxTokens());
        }
    }

    @Test
    void createResolvedCarriesDynamicTeamSkillsWithTheSameSurface() {
        ScriptedModel resolved = repeatedModel("unused resolved", 2);
        CodingSpecialistFactory factory = factory(resolved);

        try (HarnessAgent agent = factory.createResolved(
                specialistSession(),
                toolkit(new ControlledCodingTools()),
                resolved,
                Optional.empty(),
                "M10-A03b resolved prompt with dynamic skills.",
                io.crewscope.domain.agent.SafeModelGenerateOptions.defaults(),
                List.of(dynamicSkill()))) {

            assertSame(resolved, agent.getModel());
            assertEquals("M10-A03b resolved prompt with dynamic skills.",
                    agent.getDelegate().getSysPrompt());
            assertEquals(controlledSurfaceWithoutSkillLoader(),
                    agent.getToolkit().getToolNames());
        }
    }

    // ------------------------------------------------------------------ end-to-end load

    @Test
    void theSkillLoadToolServesTheDynamicRepositoryContentOnThePinnedPath() {
        ScriptedModel pinned = new ScriptedModel(
                toolResponse("plan-enter", "plan_enter", Map.of()),
                toolResponse(
                        "plan-write",
                        "plan_write",
                        Map.of("content", "1. Exit the plan\n2. Load the team skill\n3. Deliver")),
                toolResponse(
                        "plan-exit",
                        "plan_exit",
                        Map.of("summary", "Load the team skill and deliver")),
                toolResponse(
                        "skill-dynamic",
                        CodingSpecialistToolSurface.SKILL_LOAD_TOOL,
                        Map.of("skillId", DYNAMIC_KEY + "_" + dynamicSource(), "path", "SKILL.md")),
                structuredResponse(validResult()));
        AgentScopeCodingRuntime runtime = new AgentScopeCodingRuntime(
                factory(pinned),
                resolved -> new io.crewscope.agentscope.model.ResolvedAgentScopeModels(
                        pinned, Optional.empty()));

        CodingSpecialistRunResult result = runtime.execute(new CodingSpecialistRequest(
                        specialistSession(),
                        toolkit(new ControlledCodingTools()),
                        "Load the team skill and deliver.",
                        Optional.of(mock(ResolvedAgentExecutionConfiguration.class)),
                        List.of(dynamicSkill())))
                .block(Duration.ofSeconds(10));

        assertEquals(List.of("Updated both bounded fixture files"),
                result.output().changeSummary());
        assertEquals(5, pinned.callCount());
        assertTrue(pinned.request(4).stream()
                .flatMap(message -> message.getContent().stream())
                .filter(io.agentscope.core.message.ToolResultBlock.class::isInstance)
                .map(io.agentscope.core.message.ToolResultBlock.class::cast)
                .anyMatch(block -> String.valueOf(block.getOutput()).contains(DYNAMIC_BODY)),
                "the final round must carry the dynamic skill body the loader returned");
    }

    // ------------------------------------------------------------------ fixtures

    private static TeamSkillExecutionSource.PublishedTeamSkill dynamicSkill() {
        String content = "---\nname: " + DYNAMIC_KEY
                + "\ndescription: Release review checklist.\n---\n\n" + DYNAMIC_BODY;
        return new TeamSkillExecutionSource.PublishedTeamSkill(
                DYNAMIC_KEY, DYNAMIC_REVISION, content, "5".repeat(64));
    }

    /**
     * The loader addresses a skill by its composite id "{name}_{source}" across the joined
     * repositories; the dynamic one carries its revision in the source for traceability.
     */
    private static String dynamicSource() {
        return "team-skill:" + DYNAMIC_KEY + ":" + DYNAMIC_REVISION;
    }

    private CodingSpecialistFactory factory(ScriptedModel model) {
        return new CodingSpecialistFactory(
                pinnedConfigurationSource(),
                modelId -> model,
                new InMemoryAgentStateStore(),
                new CodingSpecialistSkillBundle(),
                runtimeRoot);
    }

    private static CodingSpecialistConfigurationSource pinnedConfigurationSource() {
        return (requested, version) -> new CodingSpecialistConfiguration(
                requested,
                version,
                "primary",
                Optional.empty(),
                "primary",
                "You are CrewScope's Coding Specialist on pinned coordinates.",
                30,
                2,
                0.0,
                1.0,
                8_192,
                40,
                2,
                1_024,
                64);
    }

    /** The runtime tool surface minus the per-call Skill loader, as M4-I11 pins it. */
    private static Set<String> controlledSurfaceWithoutSkillLoader() {
        Set<String> runtimeTools = new java.util.HashSet<>(
                CodingSpecialistToolSurface.runtimeTools());
        runtimeTools.remove(CodingSpecialistToolSurface.SKILL_LOAD_TOOL);
        return runtimeTools;
    }

    private static TaskAgentRuntimeSession specialistSession() {
        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        when(session.purpose()).thenReturn(TaskAgentSessionPurpose.SPECIALIST);
        when(session.canInvoke()).thenReturn(true);
        when(session.agentProfileId()).thenReturn(
                AgentProfileId.from("22222222-2222-4222-8222-222222222222"));
        when(session.agentProfileVersion()).thenReturn(1L);
        when(session.agentScopeKey()).thenReturn(new AgentScopeSessionKey(
                "crewscope:v1:user:a03b-dynamic", "crewscope:v1:session:a03b-dynamic"));
        return session;
    }

    private static Toolkit toolkit(Object tools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return toolkit;
    }

    private static ScriptedModel repeatedModel(String response, int count) {
        String[] responses = new String[count];
        java.util.Arrays.fill(responses, response);
        return new ScriptedModel(responses);
    }

    private static ChatResponse toolResponse(
            String callId, String toolName, Map<String, Object> input) {
        return ChatResponse.builder()
                .content(List.of(ToolUseBlock.builder()
                        .id(callId)
                        .name(toolName)
                        .input(input)
                        .content(JsonUtils.getJsonCodec().toJson(input))
                        .build()))
                .usage(new ChatUsage(12, 8, 0.01))
                .build();
    }

    private static ChatResponse structuredResponse(Map<String, Object> response) {
        return toolResponse("structured", "generate_response", Map.of("response", response));
    }

    private static Map<String, Object> validResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "1");
        result.put("changeSummary", List.of("Updated both bounded fixture files"));
        result.put("limitations", List.of());
        result.put("risks", List.of());
        return result;
    }

    /** The exact controlled Coding tool surface of M4-I11. */
    @SuppressWarnings("unused")
    private static final class ControlledCodingTools {

        @Tool(name = "repository_tree", description = "tree", readOnly = true)
        public String tree() { return "src/main/java/example/One.java"; }

        @Tool(name = "repository_list", description = "list", readOnly = true)
        public String list() { return "src/main/java/example/One.java"; }

        @Tool(name = "repository_read", description = "read", readOnly = true)
        public String read(@ToolParam(name = "path") String path) { return "before"; }

        @Tool(name = "repository_grep", description = "grep", readOnly = true)
        public String grep() { return "before"; }

        @Tool(name = "repository_glob", description = "glob", readOnly = true)
        public String glob() { return "src/main/java/example/One.java"; }

        @Tool(name = "repository_git_history", description = "history", readOnly = true)
        public String history() { return "baseline"; }

        @Tool(name = "repository_git_status", description = "status", readOnly = true)
        public String status() { return "status"; }

        @Tool(name = "repository_git_diff", description = "diff", readOnly = true)
        public String diff() { return "diff"; }

        @Tool(name = "coding_create", description = "create")
        public String create() { return "created"; }

        @Tool(name = "coding_edit", description = "edit")
        public String edit(
                @ToolParam(name = "path") String path,
                @ToolParam(name = "old_text") String oldText,
                @ToolParam(name = "new_text") String newText,
                @ToolParam(name = "replace_all") boolean replaceAll) { return "edited"; }

        @Tool(name = "coding_patch", description = "patch")
        public String patch() { return "patched"; }

        @Tool(name = "coding_move", description = "move")
        public String move() { return "moved"; }

        @Tool(name = "coding_delete", description = "delete")
        public String delete() { return "deleted"; }

        @Tool(name = "coding_run_command", description = "run")
        public String run(@ToolParam(name = "command_kind") String commandKind) {
            return "TEST_OK";
        }
    }
}
