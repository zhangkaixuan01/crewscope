package io.crewscope.agentscope.coding;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.ReActAgent;
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
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
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
        CodingSpecialistConfiguration configuration = configuration(session);
        return createAgent(session, toolkit, legacyRuntimeConfiguration(configuration));
    }

    /** Reuses the complete M4 Coding composition with M5's preflighted models and Template prompt. */
    public HarnessAgent createResolved(
            TaskAgentRuntimeSession runtimeSession,
            Toolkit toolkit,
            Model primaryModel,
            Optional<Model> fallbackModel,
            String systemPrompt,
            SafeModelGenerateOptions generateOptions) {
        TaskAgentRuntimeSession session = requireSpecialistSession(runtimeSession);
        CodingSpecialistToolSurface.requireControlledToolkit(toolkit);
        CodingSpecialistConfiguration operational = configuration(session);
        SafeModelGenerateOptions options = Objects.requireNonNull(generateOptions, "generateOptions");
        long maximumOutputTokens = options.maximumOutputTokens()
                .orElse((long) operational.maxOutputTokens());
        if (maximumOutputTokens > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Coding maximumOutputTokens exceeds the AgentScope integer limit");
        }
        RuntimeConfiguration runtime = new RuntimeConfiguration(
                Objects.requireNonNull(primaryModel, "primaryModel"),
                Objects.requireNonNull(fallbackModel, "fallbackModel"),
                Objects.requireNonNull(primaryModel, "primaryModel"),
                Objects.requireNonNull(systemPrompt, "systemPrompt"),
                operational.maxIterations(),
                options.maximumAttempts(),
                options.temperature()
                        .map(java.math.BigDecimal::doubleValue)
                        .orElse(operational.temperature()),
                options.topP()
                        .map(java.math.BigDecimal::doubleValue)
                        .orElse(operational.topP()),
                Math.toIntExact(maximumOutputTokens),
                operational.compactionTriggerMessages(),
                operational.compactionKeepMessages(),
                operational.toolResultEvictionChars(),
                operational.toolResultPreviewChars());
        return createAgent(session, toolkit, runtime);
    }

    private HarnessAgent createAgent(
            TaskAgentRuntimeSession session,
            Toolkit toolkit,
            RuntimeConfiguration configuration) {

        String stableName = "crewscope-coding-"
                + session.agentProfileId()
                + "-v"
                + session.agentProfileVersion();
        // Keep mutation receipts and the fixed Skill body intact. Historical repository reads
        // remain reproducible from the immutable Worktree and may be reduced to bounded previews;
        // retaining every full source read caused cumulative model input to exceed the frozen Q03
        // budget even when the delivered code and tests were correct.
        Set<String> evictionExclusions = new HashSet<>(
                CodingSpecialistToolSurface.FILESYSTEM_TOOLS);
        evictionExclusions.add(CodingSpecialistToolSurface.SKILL_LOAD_TOOL);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(stableName)
                .agentId(stableName)
                .description("CrewScope controlled Coding Specialist")
                .sysPrompt(configuration.systemPrompt())
                .model(configuration.primaryModel())
                .toolkit(toolkit)
                .middleware(new CodingSpecialistTelemetryMiddleware())
                .maxIters(configuration.maxIterations())
                .maxRetries(configuration.maxRetries())
                // Generation parameters belong to the pinned Specialist configuration. Q03 uses
                // deterministic sampling coordinates while keeping provider-specific Seed out of
                // the request because DeepSeek does not guarantee that OpenAI extension field.
                .generateOptions(GenerateOptions.builder()
                        .temperature(configuration.temperature())
                        .topP(configuration.topP())
                        .maxTokens(configuration.maxOutputTokens())
                        .build())
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
                        .model(configuration.compactionModel())
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
        configuration.fallbackModel().ifPresent(builder::fallbackModel);

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

    private RuntimeConfiguration legacyRuntimeConfiguration(
            CodingSpecialistConfiguration configuration) {
        return new RuntimeConfiguration(
                observedModel(configuration.modelId(), AgentModelRole.PRIMARY),
                configuration.fallbackModelId().map(modelId ->
                        observedModel(modelId, AgentModelRole.FALLBACK)),
                observedModel(configuration.compactionModelId(), AgentModelRole.PRIMARY),
                configuration.systemPrompt(),
                configuration.maxIterations(),
                configuration.maxRetries(),
                configuration.temperature(),
                configuration.topP(),
                configuration.maxOutputTokens(),
                configuration.compactionTriggerMessages(),
                configuration.compactionKeepMessages(),
                configuration.toolResultEvictionChars(),
                configuration.toolResultPreviewChars());
    }

    /**
     * Creates a minimal AgentScope agent for the bounded structured-delivery recovery call.
     *
     * <p>It deliberately has no repository, mutation, command, Plan or Todo tools. The primary
     * Coding Agent remains the sole owner of work, state and authority; this agent only turns the
     * already-completed task summary into the required native structured result.
     */
    ReActAgent createStructuredResultAgent(TaskAgentRuntimeSession runtimeSession) {
        TaskAgentRuntimeSession session = requireSpecialistSession(runtimeSession);
        CodingSpecialistConfiguration configuration = configuration(session);
        String stableName = "crewscope-coding-delivery-"
                + session.agentProfileId()
                + "-v"
                + session.agentProfileVersion();
        return ReActAgent.builder()
                .name(stableName)
                .sysPrompt("Return only the requested structured Coding delivery summary.")
                .model(observedModel(configuration.modelId(), AgentModelRole.PRIMARY))
                .toolkit(new Toolkit())
                .middleware(new CodingSpecialistTelemetryMiddleware())
                .maxIters(2)
                .maxRetries(configuration.maxRetries())
                .generateOptions(GenerateOptions.builder()
                        .temperature(configuration.temperature())
                        .topP(configuration.topP())
                        .maxTokens(configuration.maxOutputTokens())
                        .parallelToolCalls(false)
                        .build())
                .build();
    }

    /** Shared durable store used to rebuild an exact Specialist Session on another Worker. */
    AgentStateStore stateStore() {
        return stateStore;
    }

    private CodingSpecialistConfiguration configuration(TaskAgentRuntimeSession session) {
        CodingSpecialistConfiguration configuration = Objects.requireNonNull(
                configurationSource.load(
                        session.agentProfileId(), session.agentProfileVersion()),
                "configurationSource result");
        requirePinnedConfiguration(session, configuration);
        return configuration;
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

    private record RuntimeConfiguration(
            Model primaryModel,
            Optional<Model> fallbackModel,
            Model compactionModel,
            String systemPrompt,
            int maxIterations,
            int maxRetries,
            double temperature,
            double topP,
            int maxOutputTokens,
            int compactionTriggerMessages,
            int compactionKeepMessages,
            int toolResultEvictionChars,
            int toolResultPreviewChars) {

        private RuntimeConfiguration {
            primaryModel = Objects.requireNonNull(primaryModel, "primaryModel");
            fallbackModel = Objects.requireNonNull(fallbackModel, "fallbackModel");
            compactionModel = Objects.requireNonNull(compactionModel, "compactionModel");
            if (systemPrompt == null || systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
        }
    }
}
