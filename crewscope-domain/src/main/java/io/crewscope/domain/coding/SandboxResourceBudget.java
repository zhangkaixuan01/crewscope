package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Hard Docker sandbox resource and command-output ceilings. */
public record SandboxResourceBudget(
        SandboxNetworkMode networkMode,
        int cpuCount,
        int memoryMiB,
        int pids,
        int maxCommandDurationSeconds,
        long maxCommandOutputBytes,
        boolean readOnlyRootFilesystem) {

    public SandboxResourceBudget {
        networkMode = Objects.requireNonNull(networkMode, "networkMode");
        if (cpuCount < 1
                || memoryMiB < 64
                || pids < 1
                || maxCommandDurationSeconds < 1
                || maxCommandOutputBytes < 1) {
            throw new DomainValidationException(
                    "workspacePolicy.sandboxResourceBudget", "all resource ceilings must be positive");
        }
    }

    public boolean isNoBroaderThan(SandboxResourceBudget baseline) {
        SandboxResourceBudget required = Objects.requireNonNull(baseline, "baseline");
        return networkMode.isNoBroaderThan(required.networkMode)
                && cpuCount <= required.cpuCount
                && memoryMiB <= required.memoryMiB
                && pids <= required.pids
                && maxCommandDurationSeconds <= required.maxCommandDurationSeconds
                && maxCommandOutputBytes <= required.maxCommandOutputBytes
                && (!required.readOnlyRootFilesystem || readOnlyRootFilesystem);
    }
}
