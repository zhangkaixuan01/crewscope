package io.crewscope.application.task;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/** Transactional database arbitration for Team, Runtime and Worker concurrent Claim limits. */
public interface ClaimQuotaRepository {

    /**
     * Serializes competing quota checks and counts active leases in the caller's transaction.
     * No separate counter can drift from the authoritative Lease rows.
     */
    Decision check(QuotaQuery query);

    enum Decision {
        AVAILABLE,
        TEAM_LIMIT,
        RUNTIME_LIMIT,
        WORKER_LIMIT
    }

    record QuotaQuery(
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId,
            RuntimeWorkerId workerId,
            int teamLimit,
            int runtimeLimit,
            int workerLimit) {

        public QuotaQuery {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(teamId, "teamId");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(workerId, "workerId");
            if (teamLimit < 1 || runtimeLimit < 1 || workerLimit < 1) {
                throw new IllegalArgumentException("claim quota limits must be positive");
            }
        }
    }
}
