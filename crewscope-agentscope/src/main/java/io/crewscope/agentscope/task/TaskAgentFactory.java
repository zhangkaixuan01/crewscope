package io.crewscope.agentscope.task;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.agentscope.AgentModelRole;
import io.crewscope.agentscope.AgentScopeModelResolver;
import io.crewscope.agentscope.ObservableAgentScopeModel;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskAgentStateIdentity;
import io.crewscope.application.task.TaskPlanPublicationService;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.workspace.AgentProfileId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Creates one safely restricted HarnessAgent per immutable Task AgentProfile version. */
public final class TaskAgentFactory implements AutoCloseable {

    private static final Set<String> BUILT_IN_TOOLS = Set.of(
            "plan_enter", "plan_write", "plan_exit", "todo_write");

    private final TaskAgentConfigurationSource configurationSource;
    private final AgentScopeModelResolver modelResolver;
    private final AgentStateStore stateStore;
    private final Supplier<Toolkit> toolkitFactory;
    private final Path runtimeRoot;
    private final ConcurrentMap<ProfileVersion, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TaskAgentFactory(
            TaskAgentConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            Supplier<Toolkit> toolkitFactory,
            Path runtimeRoot) {
        this.configurationSource = Objects.requireNonNull(configurationSource, "configurationSource");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.toolkitFactory = Objects.requireNonNull(toolkitFactory, "toolkitFactory");
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot")
                .toAbsolutePath()
                .normalize();
    }

    /** Both Session and Policy must pin the same profile before a cached Agent can be selected. */
    public HarnessAgent getOrCreate(TaskExecutionRuntimeFacts facts) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        return getOrCreate(required.runtimeSession(), required.policySnapshot());
    }

    public HarnessAgent getOrCreate(
            TaskAgentRuntimeSession runtimeSession, PolicySnapshot policySnapshot) {
        if (closed.get()) {
            throw new IllegalStateException("TaskAgentFactory is closed");
        }
        TaskAgentRuntimeSession session = Objects.requireNonNull(runtimeSession, "runtimeSession");
        PolicySnapshot policy = Objects.requireNonNull(policySnapshot, "policySnapshot");
        if (!session.agentProfileId().equals(policy.agentProfileId())
                || session.agentProfileVersion() != policy.agentProfileVersion()) {
            throw new IllegalArgumentException(
                    "Task Session and PolicySnapshot must pin the same AgentProfile version");
        }
        ProfileVersion key = new ProfileVersion(
                session.agentProfileId(), session.agentProfileVersion());
        return agents.computeIfAbsent(key, this::createAgent);
    }

    public int cachedAgentCount() {
        return agents.size();
    }

    private HarnessAgent createAgent(ProfileVersion key) {
        TaskAgentConfiguration configuration = Objects.requireNonNull(
                configurationSource.load(key.agentProfileId(), key.version()),
                "configurationSource result");
        if (!configuration.agentProfileId().equals(key.agentProfileId())
                || configuration.agentProfileVersion() != key.version()) {
            throw new IllegalStateException(
                    "Task Agent configuration must match the pinned AgentProfile version");
        }
        Toolkit toolkit = Objects.requireNonNull(toolkitFactory.get(), "toolkitFactory result");
        requireControlledToolkit(toolkit);
        String stableName = TaskAgentStateIdentity.stableAgentId(
                key.agentProfileId(), key.version());
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(stableName)
                .agentId(stableName)
                .description("CrewScope controlled Task Orchestrator")
                .sysPrompt(configuration.systemPrompt())
                .model(observedModel(configuration.modelId(), AgentModelRole.PRIMARY))
                .toolkit(toolkit)
                .maxIters(configuration.maxIterations())
                .maxRetries(configuration.maxRetries())
                .stateStore(stateStore)
                .workspace(createWorkspace(key))
                .enablePlanMode()
                .enableTaskList()
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
        configuration.fallbackModelId().ifPresent(modelId -> builder.fallbackModel(
                observedModel(modelId, AgentModelRole.FALLBACK)));
        HarnessAgent agent = builder.build();
        try {
            // Harness 2.0.0 auto-registers this MessageBus helper whenever a workspace exists. M3
            // has no asynchronous external work, so remove it before exposing the Task Agent.
            agent.getToolkit().removeTool("wait_async_results");
            requireFinalToolkit(agent.getToolkit());
            return agent;
        } catch (RuntimeException exception) {
            agent.close();
            throw exception;
        }
    }

    private Model observedModel(String modelId, AgentModelRole role) {
        return new ObservableAgentScopeModel(
                Objects.requireNonNull(modelResolver.resolve(modelId), "resolved Task model"),
                role);
    }

    private static void requireControlledToolkit(Toolkit toolkit) {
        Set<String> expected = new java.util.HashSet<>(TaskPlanPublicationService.M3_CONTROLLED_TOOLS);
        expected.add(ControlledTaskPlanValidationTool.NAME);
        if (!toolkit.getToolNames().equals(expected)) {
            throw new IllegalArgumentException(
                    "Task Toolkit must contain only the controlled M3 Fixture and validation Tools");
        }
        if (!toolkit.getTool(ControlledTaskPlanValidationTool.NAME).isReadOnly()) {
            throw new IllegalArgumentException("Task plan validation Tool must be read-only");
        }
        TaskPlanPublicationService.M3_CONTROLLED_TOOLS.forEach(name -> {
            if (toolkit.getTool(name).isReadOnly()) {
                throw new IllegalArgumentException(
                        "Fixture Tools must remain blocked by AgentScope Plan Mode");
            }
        });
    }

    private static void requireFinalToolkit(Toolkit toolkit) {
        java.util.HashSet<String> expected = new java.util.HashSet<>(BUILT_IN_TOOLS);
        expected.addAll(TaskPlanPublicationService.M3_CONTROLLED_TOOLS);
        expected.add(ControlledTaskPlanValidationTool.NAME);
        if (!toolkit.getToolNames().equals(expected)) {
            throw new IllegalStateException("HarnessAgent registered an unexpected Task Tool");
        }
    }

    private Path createWorkspace(ProfileVersion key) {
        Path workspace = runtimeRoot.resolve(
                key.agentProfileId() + "-v" + key.version()).normalize();
        if (!workspace.startsWith(runtimeRoot)) {
            throw new IllegalStateException("Task Agent workspace escaped the runtime root");
        }
        try {
            return Files.createDirectories(workspace);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Task Agent runtime workspace", exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        for (HarnessAgent agent : new ArrayList<>(agents.values())) {
            try {
                agent.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        agents.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private record ProfileVersion(AgentProfileId agentProfileId, long version) {
        private ProfileVersion {
            agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
        }
    }
}
