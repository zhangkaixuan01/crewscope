package io.crewscope.application.task;

import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicySnapshot;
import java.util.EnumSet;
import java.util.Objects;

/** Maps a pinned Task policy snapshot to the Runtime features needed to execute it. */
public final class TaskRuntimeCapabilityResolver {

    private TaskRuntimeCapabilityResolver() {}

    public static RuntimeCapabilities resolve(PolicySnapshot policy) {
        EnumSet<RuntimeCapability> required = EnumSet.of(
                RuntimeCapability.TASK_EXECUTION,
                RuntimeCapability.STREAMING,
                RuntimeCapability.DURABLE_EVENT_STREAM,
                RuntimeCapability.PAUSE_RESUME,
                RuntimeCapability.CANCEL,
                RuntimeCapability.SESSION_STATE);
        for (ExecutionCapability capability : Objects.requireNonNull(policy, "policy").capabilities()) {
            switch (capability) {
                case SESSION_RESUME -> {
                    required.add(RuntimeCapability.INTERRUPT_RESUME);
                }
                case SESSION_FORK -> required.add(RuntimeCapability.SESSION_STATE);
                case PLAN -> required.add(RuntimeCapability.PLAN);
                case STRUCTURED_OUTPUT -> required.add(RuntimeCapability.STRUCTURED_OUTPUT);
                case TOOL_APPROVAL -> required.add(RuntimeCapability.EXTERNAL_TOOL);
                case SANDBOX -> required.add(RuntimeCapability.SANDBOX);
                case WORKTREE -> required.add(RuntimeCapability.WORKTREE);
                case MULTI_REPOSITORY -> required.add(RuntimeCapability.MULTI_REPOSITORY);
                case CONTEXT_USAGE -> {
                    // Context accounting is a CrewScope execution fact and needs no routed feature.
                }
            }
        }
        return new RuntimeCapabilities(required);
    }
}
