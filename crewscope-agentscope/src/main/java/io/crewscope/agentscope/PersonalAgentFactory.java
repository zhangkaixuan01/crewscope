package io.crewscope.agentscope;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.workspace.AgentProfileId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Creates and reuses one safely configured HarnessAgent for each pinned AgentProfile version. */
public final class PersonalAgentFactory implements AutoCloseable {

    private final PersonalAgentConfigurationSource configurationSource;
    private final AgentScopeModelResolver modelResolver;
    private final AgentStateStore stateStore;
    private final Supplier<Toolkit> toolkitFactory;
    private final Path runtimeRoot;
    private final java.util.List<io.agentscope.core.middleware.MiddlewareBase> middlewares;
    private final ConcurrentMap<ProfileVersion, HarnessAgent> agents = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    PersonalAgentFactory(
            PersonalAgentConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            Supplier<Toolkit> toolkitFactory,
            Path runtimeRoot) {
        this(
                configurationSource,
                modelResolver,
                stateStore,
                toolkitFactory,
                runtimeRoot,
                java.util.List.of());
    }

    /** Creates the production factory with the mandatory ordered platform Middleware set. */
    public PersonalAgentFactory(
            PersonalAgentConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            Supplier<Toolkit> toolkitFactory,
            Path runtimeRoot,
            PlatformAgentMiddlewareSet middlewareSet) {
        this(
                configurationSource,
                modelResolver,
                stateStore,
                toolkitFactory,
                runtimeRoot,
                Objects.requireNonNull(middlewareSet, "middlewareSet").ordered());
    }

    private PersonalAgentFactory(
            PersonalAgentConfigurationSource configurationSource,
            AgentScopeModelResolver modelResolver,
            AgentStateStore stateStore,
            Supplier<Toolkit> toolkitFactory,
            Path runtimeRoot,
            java.util.List<io.agentscope.core.middleware.MiddlewareBase> middlewares) {
        this.configurationSource =
                Objects.requireNonNull(configurationSource, "configurationSource");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.toolkitFactory = Objects.requireNonNull(toolkitFactory, "toolkitFactory");
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot").toAbsolutePath().normalize();
        this.middlewares = java.util.List.copyOf(Objects.requireNonNull(
                middlewares, "middlewares"));
    }

    public HarnessAgent getOrCreate(AgentRuntimeSession runtimeSession) {
        AgentRuntimeSession required = Objects.requireNonNull(runtimeSession, "runtimeSession");
        if (closed.get()) {
            throw new IllegalStateException("PersonalAgentFactory is closed");
        }
        ProfileVersion key = new ProfileVersion(
                required.agentProfileId(), required.agentProfileVersion());
        return agents.computeIfAbsent(key, this::createAgent);
    }

    int cachedAgentCount() {
        return agents.size();
    }

    private HarnessAgent createAgent(ProfileVersion key) {
        AgentScopePersonalAgentConfiguration configuration = Objects.requireNonNull(
                configurationSource.load(key.agentProfileId(), key.version()),
                "configurationSource result");
        if (!configuration.agentProfileId().equals(key.agentProfileId())
                || configuration.agentProfileVersion() != key.version()) {
            throw new IllegalStateException(
                    "Personal Agent configuration must match the pinned AgentProfile version");
        }

        Model primaryModel = observedModel(
                configuration.modelId(), AgentModelRole.PRIMARY, "resolved primary model");
        Toolkit toolkit = Objects.requireNonNull(toolkitFactory.get(), "toolkitFactory result");
        Path workspace = createWorkspace(key);
        String stableName = "crewscope-personal-"
                + key.agentProfileId()
                + "-v"
                + key.version();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(stableName)
                .agentId(stableName)
                .description("CrewScope Personal Agent runtime")
                .sysPrompt(configuration.systemPrompt())
                .model(primaryModel)
                .toolkit(toolkit)
                .maxIters(configuration.maxIterations())
                .maxRetries(configuration.maxRetries())
                .stateStore(stateStore)
                .workspace(workspace)
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                // M2 state recovery is Redis-backed; compaction is deferred until its durable
                // summary policy and disclosure contract are introduced with workspace memory.
                .disableCompaction()
                .enableAgentTracingLog(false);
        configuration.fallbackModelId().ifPresent(modelId -> builder.fallbackModel(observedModel(
                modelId, AgentModelRole.FALLBACK, "resolved fallback model")));
        // Registration order is significant because AgentScope applies Middleware as an onion.
        middlewares.forEach(builder::middleware);
        return builder.build();
    }

    private Model observedModel(String modelId, AgentModelRole role, String failureMessage) {
        Model resolved = Objects.requireNonNull(modelResolver.resolve(modelId), failureMessage);
        return new ObservableAgentScopeModel(resolved, role);
    }

    private Path createWorkspace(ProfileVersion key) {
        Path workspace = runtimeRoot.resolve(
                key.agentProfileId() + "-v" + key.version()).normalize();
        if (!workspace.startsWith(runtimeRoot)) {
            throw new IllegalStateException("Personal Agent workspace escaped the runtime root");
        }
        try {
            return Files.createDirectories(workspace);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create Personal Agent runtime workspace", exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException closeFailure = null;
        for (HarnessAgent agent : new ArrayList<>(agents.values())) {
            try {
                agent.close();
            } catch (RuntimeException exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        agents.clear();
        if (closeFailure != null) {
            throw closeFailure;
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
