package io.crewscope.domain.task;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact Tool boundary requested by a Worker, optionally narrowed to one Provider resource. */
public record TaskTokenAccessRequest(
        String tool, Optional<TaskProviderAccessRequest> providerAccess) {

    public TaskTokenAccessRequest {
        tool = PolicySnapshot.requireKeys(
                        Set.of(Objects.requireNonNull(tool, "tool")),
                        "taskTokenAccess.tool",
                        false)
                .iterator()
                .next();
        providerAccess = Objects.requireNonNull(providerAccess, "providerAccess");
    }

    public static TaskTokenAccessRequest tool(String tool) {
        return new TaskTokenAccessRequest(tool, Optional.empty());
    }

    public static TaskTokenAccessRequest provider(
            String tool, TaskProviderAccessRequest providerAccess) {
        return new TaskTokenAccessRequest(tool, Optional.of(providerAccess));
    }

    @Override
    public String toString() {
        return "TaskTokenAccessRequest[scope=[REDACTED_SCOPE]]";
    }
}
