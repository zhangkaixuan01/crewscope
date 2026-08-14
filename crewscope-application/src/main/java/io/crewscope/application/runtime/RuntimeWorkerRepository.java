package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for stable Worker identities and their current heartbeat facts. */
public interface RuntimeWorkerRepository {

    RuntimeWorker create(RuntimeWorker worker);

    RuntimeWorker update(RuntimeWorker worker);

    Optional<RuntimeWorker> findById(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            RuntimeWorkerId workerId);

    Optional<RuntimeWorker> findByStableKey(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId,
            String stableKey);

    List<RuntimeWorker> findByRuntime(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId);
}
