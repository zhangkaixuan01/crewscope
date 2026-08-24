package io.crewscope.application.task;

import java.util.List;
import java.util.Objects;

/** Safe member projection joining the current Agent, Review and external delivery facts. */
public record TaskDeliverySummary(
        String taskId,
        String taskStatus,
        String taskExecutionId,
        Integer attempt,
        AgentSummary agent,
        ReviewSummary review,
        ActionSummary action) {

    public TaskDeliverySummary {
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskStatus = Objects.requireNonNull(taskStatus, "taskStatus");
    }

    /** Public Agent and model coordinates omit Connection, credential and policy hashes. */
    public record AgentSummary(
            String profileId,
            String templateKey,
            long templateVersion,
            long configurationRevision,
            String executionScope,
            String bindingSource,
            ModelSummary primaryModel,
            ModelSummary fallbackModel) {}

    public record ModelSummary(String provider, String model, long catalogRevision) {}

    public record ReviewSummary(
            String requestId,
            long requestRevision,
            String status,
            int findingCount,
            int blockerCount,
            int highCount,
            String gateDecision,
            long modificationRound) {}

    /** Safe action state excludes claims, leases, fencing, idempotency keys and raw external IDs. */
    public record ActionSummary(
            String bundleId,
            long bundleVersion,
            String bundleDigest,
            String validity,
            String confirmationStatus,
            String repository,
            List<ActionStageSummary> stages) {

        public ActionSummary {
            stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        }
    }

    public record ActionStageSummary(
            String kind,
            String dispatchStatus,
            String receiptResult,
            String externalStatus,
            String externalObjectType,
            String externalIdentityHash) {}
}
