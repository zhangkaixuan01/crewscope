package io.crewscope.application.task;

import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentStateSnapshot;
import io.crewscope.domain.task.AgentStateSnapshotId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for committed AgentStateSnapshot metadata and recovery candidates. */
public interface AgentStateSnapshotRepository {

    /**
     * Atomically inserts the new CURRENT snapshot and supersedes the previous CURRENT snapshot.
     * Snapshot and checkpoint sequences are unique and strictly increasing per Session.
     */
    AgentStateSnapshot publish(
            Optional<AgentStateSnapshot> supersededCurrent,
            AgentStateSnapshot currentSnapshot);

    AgentStateSnapshot update(AgentStateSnapshot snapshot);

    Optional<AgentStateSnapshot> findById(
            OrganizationId organizationId, AgentStateSnapshotId snapshotId);

    Optional<AgentStateSnapshot> findCurrentBySession(
            OrganizationId organizationId, AgentRuntimeSessionId sessionId);

    /** Includes INVALID metadata so new monotonic sequences never reuse a rejected checkpoint. */
    Optional<AgentStateSnapshot> findLatestBySession(
            OrganizationId organizationId, AgentRuntimeSessionId sessionId);

    /** Returns CURRENT and SUPERSEDED recovery candidates by checkpointSequence descending. */
    List<AgentStateSnapshot> findRecoveryCandidates(
            OrganizationId organizationId, AgentRunId agentRunId, int limit);
}
