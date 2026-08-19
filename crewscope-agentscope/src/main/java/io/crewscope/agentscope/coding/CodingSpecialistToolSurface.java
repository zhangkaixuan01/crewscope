package io.crewscope.agentscope.coding;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.runtime.SkillLoadTool;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Frozen M4 Coding Tool names and read-only metadata enforced before every model call. */
public final class CodingSpecialistToolSurface {

    public static final Set<String> REPOSITORY_TOOLS = Set.of(
            "repository_tree",
            "repository_list",
            "repository_read",
            "repository_grep",
            "repository_glob",
            "repository_git_history",
            "repository_git_status",
            "repository_git_diff");
    public static final Set<String> FILESYSTEM_TOOLS = Set.of(
            "coding_create", "coding_edit", "coding_patch", "coding_move", "coding_delete");
    public static final String COMMAND_TOOL = "coding_run_command";
    public static final Set<String> PLAN_AND_TASK_TOOLS = Set.of(
            "plan_enter", "plan_write", "plan_exit", "todo_write");
    public static final String SKILL_LOAD_TOOL = SkillLoadTool.TOOL_NAME;

    private static final Set<String> CONTROLLED_TOOLS;
    private static final Set<String> INITIAL_TOOLS;
    private static final Set<String> RUNTIME_TOOLS;

    static {
        HashSet<String> controlled = new HashSet<>(REPOSITORY_TOOLS);
        controlled.addAll(FILESYSTEM_TOOLS);
        controlled.add(COMMAND_TOOL);
        CONTROLLED_TOOLS = Set.copyOf(controlled);

        HashSet<String> initial = new HashSet<>(controlled);
        initial.addAll(PLAN_AND_TASK_TOOLS);
        INITIAL_TOOLS = Set.copyOf(initial);

        HashSet<String> runtime = new HashSet<>(initial);
        runtime.add(SKILL_LOAD_TOOL);
        RUNTIME_TOOLS = Set.copyOf(runtime);
    }

    private CodingSpecialistToolSurface() {}

    public static Set<String> controlledTools() {
        return CONTROLLED_TOOLS;
    }

    public static Set<String> runtimeTools() {
        return RUNTIME_TOOLS;
    }

    /** Rejects missing, extra and incorrectly classified tools before Harness adds built-ins. */
    static void requireControlledToolkit(Toolkit toolkit) {
        Toolkit required = Objects.requireNonNull(toolkit, "toolkit");
        if (!required.getToolNames().equals(CONTROLLED_TOOLS)) {
            throw new IllegalArgumentException(
                    "Coding Toolkit must contain exactly the frozen CrewScope Tool surface");
        }
        for (String name : CONTROLLED_TOOLS) {
            AgentTool tool = Objects.requireNonNull(required.getTool(name), "tool " + name);
            boolean expectedReadOnly = REPOSITORY_TOOLS.contains(name);
            if (tool.isReadOnly() != expectedReadOnly) {
                throw new IllegalArgumentException(
                        "Coding Tool has invalid read-only metadata: " + name);
            }
        }
    }

    /** Rejects Builder defaults, MCP, raw tools and late skill mutations after a call begins. */
    static void requireRuntimeToolkit(Toolkit toolkit, boolean skillInstalled) {
        Set<String> expected = skillInstalled ? RUNTIME_TOOLS : INITIAL_TOOLS;
        if (!Objects.requireNonNull(toolkit, "toolkit").getToolNames().equals(expected)) {
            throw new IllegalStateException("HarnessAgent registered an unexpected Coding Tool");
        }
        if (skillInstalled && !toolkit.getTool(SKILL_LOAD_TOOL).isReadOnly()) {
            throw new IllegalStateException("Coding Skill loader must remain read-only");
        }
    }

    /** Stable diagnostics for tests and startup checks without exposing Tool implementation data. */
    public static Map<String, Set<String>> summary() {
        return Map.of(
                "repository", REPOSITORY_TOOLS,
                "filesystem", FILESYSTEM_TOOLS,
                "runtime", RUNTIME_TOOLS);
    }
}
