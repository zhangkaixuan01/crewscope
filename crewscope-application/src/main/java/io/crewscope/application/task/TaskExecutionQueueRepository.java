package io.crewscope.application.task;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL-backed READY queue Port with stable keyset and lock-aware batch reads. */
public interface TaskExecutionQueueRepository {

    /**
     * Reads a stable page ordered by priority DESC, notBefore ASC, createdAt ASC and ID ASC.
     * The cursor contains the complete ordering tuple, so concurrent inserts cannot create offset
     * drift.
     */
    ReadyPage findReadyPage(ReadyQuery query);

    /**
     * Locks a bounded READY batch with {@code FOR UPDATE SKIP LOCKED}. Callers must invoke this
     * method inside the surrounding Claim transaction that consumes the locked candidates.
     */
    List<TaskExecution> lockReadyBatch(ReadyQuery query);

    record ReadyQuery(
            OrganizationId organizationId,
            Optional<TeamId> teamId,
            UtcTimestamp authoritativeNow,
            Optional<ReadyCursor> after,
            int limit) {
        public ReadyQuery {
            Objects.requireNonNull(organizationId, "organizationId");
            teamId = Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(authoritativeNow, "authoritativeNow");
            after = Objects.requireNonNull(after, "after");
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
        }
    }

    record ReadyCursor(
            int priority,
            UtcTimestamp notBefore,
            UtcTimestamp createdAt,
            TaskExecutionId executionId) {
        public ReadyCursor {
            if (priority < 0 || priority > 100) {
                throw new IllegalArgumentException("priority must be between 0 and 100");
            }
            Objects.requireNonNull(notBefore, "notBefore");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(executionId, "executionId");
        }
    }

    record ReadyPage(List<TaskExecution> executions, Optional<ReadyCursor> nextCursor) {
        public ReadyPage {
            executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }
}
