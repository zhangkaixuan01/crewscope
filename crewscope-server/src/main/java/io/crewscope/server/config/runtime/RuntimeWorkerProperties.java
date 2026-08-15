package io.crewscope.server.config.runtime;

import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.runtime.ExecutionLeaseCoordinatorSpec;
import io.crewscope.infrastructure.runtime.TaskClaimSchedulerSpec;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe deployment configuration for the M3 persistent Runtime Registry and JVM Worker. */
@ConfigurationProperties(prefix = "crewscope.runtime")
public class RuntimeWorkerProperties {

    private String executionProfile = "all";
    private Registry registry = new Registry();
    private Scheduler scheduler = new Scheduler();

    public String getExecutionProfile() {
        return executionProfile;
    }

    public void setExecutionProfile(String executionProfile) {
        this.executionProfile = executionProfile;
    }

    public Registry getRegistry() {
        return registry;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    RuntimeWorkerRegistrationSpec registrationSpec(
            RuntimeDeploymentProfile profile, io.crewscope.domain.identity.Principal actor) {
        Registry configured = requireRegistry();
        Worker worker = configured.requireWorker();
        RuntimeCapabilities runtimeCapabilities = new RuntimeCapabilities(
                Set.copyOf(configured.capabilities),
                Set.copyOf(configured.languages),
                Set.copyOf(configured.buildSystems));
        RuntimeCapabilities workerCapabilities = worker.capabilities.isEmpty()
                ? runtimeCapabilities
                : new RuntimeCapabilities(
                        Set.copyOf(worker.capabilities),
                        Set.copyOf(worker.languages),
                        Set.copyOf(worker.buildSystems));
        return new RuntimeWorkerRegistrationSpec(
                parseOrganizationId(configured.organizationId),
                new RuntimeEnvironment(configured.environment),
                configured.runtimeKey,
                configured.displayName,
                configured.implementationVersion,
                runtimeCapabilities,
                worker.stableKey,
                profile.workerProfile(),
                workerCapabilities,
                worker.maxConcurrentExecutions,
                worker.heartbeatInterval,
                worker.heartbeatTimeout,
                actor);
    }

    TaskClaimSchedulerSpec claimSchedulerSpec(RuntimeWorkerRegistrationSpec registrationSpec) {
        RuntimeWorkerRegistrationSpec registration = java.util.Objects.requireNonNull(
                registrationSpec, "registrationSpec");
        Scheduler configured = requireScheduler();
        return new TaskClaimSchedulerSpec(
                registration.organizationId(),
                registration.environment(),
                registration.runtimeKey(),
                registration.workerStableKey(),
                registration.actor(),
                registration.heartbeatTimeout(),
                configured.prepareLeaseDuration,
                configured.teamConcurrentLimit,
                configured.runtimeConcurrentLimit,
                configured.maximumBatchSize,
                configured.maximumScanSize);
    }

    ExecutionLeaseCoordinatorSpec executionLeaseCoordinatorSpec(
            RuntimeWorkerRegistrationSpec registrationSpec) {
        RuntimeWorkerRegistrationSpec registration = java.util.Objects.requireNonNull(
                registrationSpec, "registrationSpec");
        Scheduler configured = requireScheduler();
        return new ExecutionLeaseCoordinatorSpec(
                registration.organizationId(),
                registration.environment(),
                registration.actor(),
                configured.prepareLeaseDuration,
                configured.runLeaseDuration,
                configured.leaseHeartbeatInterval,
                configured.leaseHeartbeatJitterTolerance,
                configured.leaseSweeperInterval,
                configured.maximumSweepSize);
    }

    OrganizationId organizationId() {
        return parseOrganizationId(requireRegistry().organizationId);
    }

    io.crewscope.domain.shared.id.PrincipalId actorPrincipalId() {
        String value = requireRegistry().actorPrincipalId;
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "crewscope.runtime.registry.actor-principal-id must not be blank");
        }
        try {
            return io.crewscope.domain.shared.id.PrincipalId.from(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "crewscope.runtime.registry.actor-principal-id must be a canonical UUID",
                    exception);
        }
    }

    private Registry requireRegistry() {
        if (registry == null) {
            throw new IllegalStateException("crewscope.runtime.registry must be configured");
        }
        return registry;
    }

    private Scheduler requireScheduler() {
        if (scheduler == null) {
            throw new IllegalStateException("crewscope.runtime.scheduler must be configured");
        }
        return scheduler;
    }

    private static OrganizationId parseOrganizationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "crewscope.runtime.registry.organization-id must not be blank");
        }
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "crewscope.runtime.registry.organization-id must be a canonical UUID",
                    exception);
        }
    }

    /** Runtime-wide stable identity and published capability snapshot. */
    public static class Registry {

        private String organizationId = "";
        private String actorPrincipalId = "";
        private String environment = "development";
        private String runtimeKey = "agentscope-java";
        private String displayName = "AgentScope Java";
        private String implementationVersion = "2.0.0";
        private Set<RuntimeCapability> capabilities = EnumSet.of(
                RuntimeCapability.CONVERSATION,
                RuntimeCapability.STREAMING,
                RuntimeCapability.STRUCTURED_OUTPUT,
                RuntimeCapability.INTERRUPT_RESUME,
                RuntimeCapability.CANCEL,
                RuntimeCapability.SESSION_STATE,
                RuntimeCapability.PLAN,
                RuntimeCapability.EXTERNAL_TOOL,
                RuntimeCapability.DISTRIBUTED_STATE);
        private Set<String> languages = new LinkedHashSet<>();
        private Set<String> buildSystems = new LinkedHashSet<>();
        private Worker worker = new Worker();

        public String getOrganizationId() { return organizationId; }
        public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
        public String getActorPrincipalId() { return actorPrincipalId; }
        public void setActorPrincipalId(String actorPrincipalId) { this.actorPrincipalId = actorPrincipalId; }
        public String getEnvironment() { return environment; }
        public void setEnvironment(String environment) { this.environment = environment; }
        public String getRuntimeKey() { return runtimeKey; }
        public void setRuntimeKey(String runtimeKey) { this.runtimeKey = runtimeKey; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getImplementationVersion() { return implementationVersion; }
        public void setImplementationVersion(String implementationVersion) {
            this.implementationVersion = implementationVersion;
        }
        public Set<RuntimeCapability> getCapabilities() { return capabilities; }
        public void setCapabilities(Set<RuntimeCapability> capabilities) {
            this.capabilities = capabilities;
        }
        public Set<String> getLanguages() { return languages; }
        public void setLanguages(Set<String> languages) { this.languages = languages; }
        public Set<String> getBuildSystems() { return buildSystems; }
        public void setBuildSystems(Set<String> buildSystems) { this.buildSystems = buildSystems; }
        public Worker getWorker() { return worker; }
        public void setWorker(Worker worker) { this.worker = worker; }

        private Worker requireWorker() {
            if (worker == null) {
                throw new IllegalStateException(
                        "crewscope.runtime.registry.worker must be configured");
            }
            return worker;
        }
    }

    /** Stable identity, routable capability subset, capacity and heartbeat policy for this JVM. */
    public static class Worker {

        private String stableKey = "";
        private Set<RuntimeCapability> capabilities = EnumSet.noneOf(RuntimeCapability.class);
        private Set<String> languages = new LinkedHashSet<>();
        private Set<String> buildSystems = new LinkedHashSet<>();
        private int maxConcurrentExecutions = 4;
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration heartbeatTimeout = Duration.ofSeconds(30);

        public String getStableKey() { return stableKey; }
        public void setStableKey(String stableKey) { this.stableKey = stableKey; }
        public Set<RuntimeCapability> getCapabilities() { return capabilities; }
        public void setCapabilities(Set<RuntimeCapability> capabilities) {
            this.capabilities = capabilities;
        }
        public Set<String> getLanguages() { return languages; }
        public void setLanguages(Set<String> languages) { this.languages = languages; }
        public Set<String> getBuildSystems() { return buildSystems; }
        public void setBuildSystems(Set<String> buildSystems) { this.buildSystems = buildSystems; }
        public int getMaxConcurrentExecutions() { return maxConcurrentExecutions; }
        public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
            this.maxConcurrentExecutions = maxConcurrentExecutions;
        }
        public Duration getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }
        public Duration getHeartbeatTimeout() { return heartbeatTimeout; }
        public void setHeartbeatTimeout(Duration heartbeatTimeout) {
            this.heartbeatTimeout = heartbeatTimeout;
        }
    }

    /** Bounded Claim, PREPARE Lease and multi-tenant concurrency policy. */
    public static class Scheduler {

        private Duration prepareLeaseDuration = Duration.ofSeconds(30);
        private Duration runLeaseDuration = Duration.ofSeconds(30);
        private Duration leaseHeartbeatInterval = Duration.ofSeconds(10);
        private Duration leaseHeartbeatJitterTolerance = Duration.ofSeconds(5);
        private Duration leaseSweeperInterval = Duration.ofSeconds(5);
        private int teamConcurrentLimit = 8;
        private int runtimeConcurrentLimit = 32;
        private int maximumBatchSize = 8;
        private int maximumScanSize = 32;
        private int maximumSweepSize = 100;

        public Duration getPrepareLeaseDuration() { return prepareLeaseDuration; }
        public void setPrepareLeaseDuration(Duration prepareLeaseDuration) {
            this.prepareLeaseDuration = prepareLeaseDuration;
        }
        public Duration getRunLeaseDuration() { return runLeaseDuration; }
        public void setRunLeaseDuration(Duration runLeaseDuration) {
            this.runLeaseDuration = runLeaseDuration;
        }
        public Duration getLeaseHeartbeatInterval() { return leaseHeartbeatInterval; }
        public void setLeaseHeartbeatInterval(Duration leaseHeartbeatInterval) {
            this.leaseHeartbeatInterval = leaseHeartbeatInterval;
        }
        public Duration getLeaseHeartbeatJitterTolerance() {
            return leaseHeartbeatJitterTolerance;
        }
        public void setLeaseHeartbeatJitterTolerance(Duration value) {
            this.leaseHeartbeatJitterTolerance = value;
        }
        public Duration getLeaseSweeperInterval() { return leaseSweeperInterval; }
        public void setLeaseSweeperInterval(Duration leaseSweeperInterval) {
            this.leaseSweeperInterval = leaseSweeperInterval;
        }
        public int getTeamConcurrentLimit() { return teamConcurrentLimit; }
        public void setTeamConcurrentLimit(int teamConcurrentLimit) {
            this.teamConcurrentLimit = teamConcurrentLimit;
        }
        public int getRuntimeConcurrentLimit() { return runtimeConcurrentLimit; }
        public void setRuntimeConcurrentLimit(int runtimeConcurrentLimit) {
            this.runtimeConcurrentLimit = runtimeConcurrentLimit;
        }
        public int getMaximumBatchSize() { return maximumBatchSize; }
        public void setMaximumBatchSize(int maximumBatchSize) {
            this.maximumBatchSize = maximumBatchSize;
        }
        public int getMaximumScanSize() { return maximumScanSize; }
        public void setMaximumScanSize(int maximumScanSize) {
            this.maximumScanSize = maximumScanSize;
        }
        public int getMaximumSweepSize() { return maximumSweepSize; }
        public void setMaximumSweepSize(int maximumSweepSize) {
            this.maximumSweepSize = maximumSweepSize;
        }
    }
}
