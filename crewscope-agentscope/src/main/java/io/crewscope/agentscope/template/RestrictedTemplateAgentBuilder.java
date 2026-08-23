package io.crewscope.agentscope.template;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.PlatformAgentMiddlewareSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Shared safe Harness builder for non-Coding template roles. */
public final class RestrictedTemplateAgentBuilder {

    private final AgentStateStore stateStore;
    private final Path runtimeRoot;
    private final int maximumIterations;
    private final java.util.List<io.agentscope.core.middleware.MiddlewareBase> middlewares;

    public RestrictedTemplateAgentBuilder(
            AgentStateStore stateStore,
            Path runtimeRoot,
            int maximumIterations,
            PlatformAgentMiddlewareSet middlewareSet) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot")
                .toAbsolutePath()
                .normalize();
        if (maximumIterations < 1 || maximumIterations > 200) {
            throw new IllegalArgumentException("maximumIterations must be between 1 and 200");
        }
        this.maximumIterations = maximumIterations;
        this.middlewares = Objects.requireNonNull(middlewareSet, "middlewareSet").ordered();
    }

    public HarnessAgent build(TemplateAgentBuildRequest request, String description) {
        TemplateAgentBuildRequest required = Objects.requireNonNull(request, "request");
        if (!required.definition().configuration().approvedSkillKeys().isEmpty()) {
            throw new IllegalArgumentException(
                    "This non-Coding Template runtime has no registered immutable Skill bundle");
        }
        String stableName = stableName(required);
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(stableName)
                .agentId(stableName)
                .description(requireDescription(description))
                .sysPrompt(required.definition().systemPrompt())
                .model(required.definition().primaryModel())
                .toolkit(required.toolkit())
                .maxIters(maximumIterations)
                .maxRetries(required.definition().configuration()
                        .generateOptions()
                        .maximumAttempts())
                .stateStore(stateStore)
                .workspace(createWorkspace(required))
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .disableCompaction()
                .enableAgentTracingLog(false);
        required.definition().fallbackModel().ifPresent(builder::fallbackModel);
        middlewares.forEach(builder::middleware);
        HarnessAgent agent = builder.build();
        try {
            agent.getToolkit().removeTool("wait_async_results");
            if (!agent.getToolkit().getToolNames().equals(
                    required.definition().enabledToolNames())) {
                throw new IllegalStateException(
                        "HarnessAgent registered a Tool outside the exact Template surface");
            }
            return agent;
        } catch (RuntimeException exception) {
            agent.close();
            throw exception;
        }
    }

    private String stableName(TemplateAgentBuildRequest request) {
        AgentTemplateRuntimeDefinition definition = request.definition();
        return "crewscope-template-"
                + definition.template().templateVersion().key()
                + "-v"
                + definition.template().templateVersion().version()
                + "-"
                + definition.profile().id()
                + "-c"
                + definition.configuration().revision().value();
    }

    private Path createWorkspace(TemplateAgentBuildRequest request) {
        AgentTemplateRuntimeDefinition definition = request.definition();
        Path workspace = runtimeRoot
                .resolve(definition.profile().id()
                        + "-c"
                        + definition.configuration().revision().value())
                .normalize();
        if (!workspace.startsWith(runtimeRoot)) {
            throw new IllegalStateException("Template Agent workspace escaped the runtime root");
        }
        try {
            return Files.createDirectories(workspace);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Template Agent runtime workspace", exception);
        }
    }

    private static String requireDescription(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 500) {
            throw new IllegalArgumentException("description contains invalid text");
        }
        return value.strip();
    }
}
