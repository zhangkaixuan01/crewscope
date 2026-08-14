package io.crewscope.application.runtime;

import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for Organization and environment scoped runtime registry entries. */
public interface ExecutionRuntimeRepository {

    ExecutionRuntime create(ExecutionRuntime runtime);

    ExecutionRuntime update(ExecutionRuntime runtime);

    Optional<ExecutionRuntime> findById(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId);

    Optional<ExecutionRuntime> findByKey(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            String runtimeKey);

    List<ExecutionRuntime> findByEnvironment(
            OrganizationId organizationId, RuntimeEnvironment environment);
}
