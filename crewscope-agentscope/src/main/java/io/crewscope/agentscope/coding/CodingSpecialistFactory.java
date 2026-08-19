package io.crewscope.agentscope.coding;

import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.crewscope.agentscope.AgentModelRole;
import io.crewscope.agentscope.AgentScopeModelResolver;
import io.crewscope.agentscope.ObservableAgentScopeModel;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Creates one short-lived, stateful HarnessAgent over an invocation-bound Coding Toolkit. */
public final class CodingSpecialistFactory {

    private final CodingSpecialistConfigurationSource configurationSource;
    private final AgentScopeModelResolver modelResolver;
    private final AgentStateStore stateStore;
    private final CodingSpecialistSkillBundle skillBundle;
    private final Path runtimeRoot;

    public CodingSpecialistFactory(
            CodingSpecialistConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            CodingSpecialistSkillBundle skillBundle,
            Path runtimeRoot) {
        this.configurationSource = Objects.requireNonNull(
                configurationSource, "configurationSource");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.skillBundle = Objects.requireNonNull(skillBundle, "skillBundle");
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot")
                .toAbsolutePath()
                .normalize();
    }

    public HarnessAgent create(TaskAgentRuntimeSession runtimeSession, Toolkit toolkit) {
        TaskAgentRuntimeSession session = requireSpecialistSession(runtimeSession);
        CodingSpecialistToolSurface.requireControlledToolkit(toolkit);
        CodingSpecialistConfiguration configuration = Objects.requireNonNull(
                configurationSource.load(
                        session.agentProfileId(), session.agentProfileVersion()),
                "configurationSource result");
        requirePinnedConfiguration(session, configuration);

        String stableName = "crewscope-coding-"
                + session.agentProfileId()
                + "-v"
                + session.agentProfileVersion();
        Model primary = observedModel(configuration.modelId(), AgentModelRole.PRIMARY);
        Model compaction = observedModel(
                configuration.compactionModelId(), AgentModelRole.PRIMARY);
        Set<String> evictionExclusions = new HashSet<>(
                CodingSpecialistToolSurface.REPOSITORY_TOOLS);
        evictionExclusions.addAll(CodingSpecialistToolSurface.FILESYSTEM_TOOLS);
        evictionExclusions.add(CodingSpecialistToolSurface.SKILL_LOAD_TOOL);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(stableName)
                .agentId(stableName)
                .description("CrewScope controlled Coding Specialist")
                .sysPrompt(configuration.systemPrompt())
                .model(primary)
                .toolkit(toolkit)
                .maxIters(configuration.maxIterations())
                .maxRetries(configuration.maxRetries())
                .stateStore(stateStore)
                .workspace(createWorkspace(session))
                // AgentScope defaults local workspaces to USER isolation. CrewScope reuses one
                // Coding Principal across Task executions, so Plan files must be namespaced by
                // the durable AgentScope Session instead of being shared by that Principal.
                .filesystem(new LocalFilesystemSpec().isolationScope(IsolationScope.SESSION))
                // CrewScope Tools already enforce Policy, Lease and Fencing on every call. M4-I12
                // owns platform Pause/Approval state, so AgentScope does not add a second prompt.
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.BYPASS)
                        .build())
                .enablePlanMode()
                .enableTaskList()
                .compaction(CompactionConfig.builder()
                        .model(compaction)
                        .triggerMessages(configuration.compactionTriggerMessages())
                        .triggerTokens(Integer.MAX_VALUE)
                        .keepMessages(configuration.compactionKeepMessages())
                        .keepTokens(0)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(false)
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.builder()
                        .maxResultChars(configuration.toolResultEvictionChars())
                        .previewChars(configuration.toolResultPreviewChars())
                        .excludedToolNames(evictionExclusions)
                        .build())
                .skillFilter(SkillFilter.only(CodingSpecialistSkillBundle.SKILL_NAME))
                .disableDefaultWorkspaceSkills()
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .enableAgentTracingLog(false);
        configuration.fallbackModelId().ifPresent(modelId -> builder.fallbackModel(
                observedModel(modelId, AgentModelRole.FALLBACK)));

        // AgentScope 2.0 installs its read-only Skill loader through per-call middleware. The
        // single classpath repository, its content hash and the filter above are immutable.
        ClasspathSkillRepository skillRepository = skillBundle.openRepository();
        HarnessAgent agent;
        try {
            agent = builder.skillRepository(skillRepository).build();
        } catch (RuntimeException exception) {
            skillRepository.close();
            throw exception;
        }
        try {
            // AgentScope creates this helper for any workspace-backed MessageBus. Coding uses
            // synchronous platform tools, so the asynchronous channel remains outside M4.
            agent.getToolkit().removeTool("wait_async_results");
            CodingSpecialistToolSurface.requireRuntimeToolkit(agent.getToolkit(), false);
            if (agent.getCompactionHook() == null) {
                throw new IllegalStateException("Coding Compaction middleware is absent");
            }
            return agent;
        } catch (RuntimeException exception) {
            agent.close();
            throw exception;
        }
    }

    /** Shared durable store used to rebuild an exact Specialist Session on another Worker. */
    AgentStateStore stateStore() {
        return stateStore;
    }

    private Model observedModel(String modelId, AgentModelRole role) {
        return new ObservableAgentScopeModel(
                Objects.requireNonNull(modelResolver.resolve(modelId), "resolved Coding model"),
                role);
    }

    private Path createWorkspace(TaskAgentRuntimeSession session) {
        Path workspace = runtimeRoot
                .resolve(session.agentProfileId() + "-v" + session.agentProfileVersion())
                .normalize();
        if (!workspace.startsWith(runtimeRoot)) {
            throw new IllegalStateException("Coding Agent workspace escaped the runtime root");
        }
        try {
            return Files.createDirectories(workspace);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Coding Agent runtime workspace", exception);
        }
    }

    private static TaskAgentRuntimeSession requireSpecialistSession(
            TaskAgentRuntimeSession runtimeSession) {
        TaskAgentRuntimeSession session = Objects.requireNonNull(runtimeSession, "runtimeSession");
        if (session.purpose() != TaskAgentSessionPurpose.SPECIALIST || !session.canInvoke()) {
            throw new IllegalArgumentException(
                    "Coding Agent requires an active Specialist RuntimeSession");
        }
        return session;
    }

    private static void requirePinnedConfiguration(
            TaskAgentRuntimeSession session, CodingSpecialistConfiguration configuration) {
        if (!configuration.agentProfileId().equals(session.agentProfileId())
                || configuration.agentProfileVersion() != session.agentProfileVersion()) {
            throw new IllegalStateException(
                    "Coding configuration must match the pinned AgentProfile version");
        }
    }
}
