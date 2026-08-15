package io.crewscope.infrastructure.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.time.Duration;
import java.util.Objects;

/** Validated, immutable deployment facts used to register one JVM Runtime Worker. */
public record RuntimeWorkerRegistrationSpec(
        OrganizationId organizationId,
        RuntimeEnvironment environment,
        String runtimeKey,
        String runtimeDisplayName,
        String implementationVersion,
        RuntimeCapabilities runtimeCapabilities,
        String workerStableKey,
        RuntimeProfile workerProfile,
        RuntimeCapabilities workerCapabilities,
        int maxConcurrentExecutions,
        Duration heartbeatInterval,
        Duration heartbeatTimeout,
        Principal actor) {

    public RuntimeWorkerRegistrationSpec {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        environment = Objects.requireNonNull(environment, "environment");
        runtimeKey = requireText(runtimeKey, "runtime.registry.runtimeKey");
        runtimeDisplayName = requireText(runtimeDisplayName, "runtime.registry.displayName");
        implementationVersion = requireText(
                implementationVersion, "runtime.registry.implementationVersion");
        runtimeCapabilities = Objects.requireNonNull(runtimeCapabilities, "runtimeCapabilities");
        workerStableKey = requireText(workerStableKey, "runtime.registry.worker.stableKey");
        workerProfile = Objects.requireNonNull(workerProfile, "workerProfile");
        workerCapabilities = Objects.requireNonNull(workerCapabilities, "workerCapabilities");
        heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        actor = Objects.requireNonNull(actor, "actor");

        if (runtimeCapabilities.values().isEmpty()) {
            throw invalid("runtime.registry.capabilities", "must not be empty");
        }
        if (!workerCapabilities.isSubsetOf(runtimeCapabilities)) {
            throw invalid(
                    "runtime.registry.worker.capabilities",
                    "must be a subset of Runtime capabilities");
        }
        if (maxConcurrentExecutions < 1
                || maxConcurrentExecutions
                        > RuntimeWorkerCapacity.MAX_CONCURRENT_EXECUTIONS) {
            throw invalid(
                    "runtime.registry.worker.maxConcurrentExecutions",
                    "must be between 1 and 10000");
        }
        if (heartbeatTimeout.compareTo(RuntimeWorker.MIN_HEARTBEAT_TIMEOUT) < 0
                || heartbeatTimeout.compareTo(RuntimeWorker.MAX_HEARTBEAT_TIMEOUT) > 0) {
            throw invalid(
                    "runtime.registry.worker.heartbeatTimeout",
                    "must be between 5 seconds and 10 minutes");
        }
        if (heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()
                || heartbeatInterval.toMillis() < 1
                || heartbeatInterval.compareTo(heartbeatTimeout) >= 0) {
            throw invalid(
                    "runtime.registry.worker.heartbeatInterval",
                    "must be positive and shorter than heartbeatTimeout");
        }
        if (!runtimeKey.matches("[a-z][a-z0-9-]{2,63}")) {
            throw invalid(
                    "runtime.registry.runtimeKey",
                    "must use a stable lowercase kebab-case key");
        }
        if (!implementationVersion.matches(
                "[0-9]+(?:\\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?")) {
            throw invalid(
                    "runtime.registry.implementationVersion",
                    "must use a semantic numeric version");
        }
        if (!workerStableKey.matches("[a-z0-9][a-z0-9._:-]{0,127}")) {
            throw invalid(
                    "runtime.registry.worker.stableKey",
                    "must use a safe stable Worker key");
        }
        if (!actor.canAct() || !actor.scope().organizationId().equals(organizationId)) {
            throw invalid(
                    "runtime.registry.actorPrincipalId",
                    "must reference an active Principal in the Runtime Organization");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "must not be blank");
        }
        return value.strip();
    }

    private static DomainValidationException invalid(String field, String message) {
        return new DomainValidationException(field, message);
    }
}
