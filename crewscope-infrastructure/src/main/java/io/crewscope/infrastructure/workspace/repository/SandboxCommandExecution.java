package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.CommandSpec;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Platform-observed bounded result returned by the trusted BuildProfile runner. */
record SandboxCommandExecution(
        CommandSpec commandSpec,
        UtcTimestamp startedAt,
        UtcTimestamp finishedAt,
        CommandTermination termination,
        Optional<Integer> exitCode,
        String stdout,
        String stderr,
        boolean outputTruncated) {

    SandboxCommandExecution {
        Objects.requireNonNull(commandSpec, "commandSpec");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(termination, "termination");
        exitCode = Objects.requireNonNull(exitCode, "exitCode");
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
