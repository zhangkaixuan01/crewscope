package io.crewscope.domain.task;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Current wait fact kept separately from the execution lifecycle status. */
public record TaskExecutionWaiting(TaskExecutionWaitReason reason, UtcTimestamp waitingSince) {

    public TaskExecutionWaiting {
        reason = Objects.requireNonNull(reason, "reason");
        waitingSince = Objects.requireNonNull(waitingSince, "waitingSince");
    }
}
