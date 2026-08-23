package io.crewscope.domain.review;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Exact Reviewer Agent, Template, Configuration and PolicySnapshot execution authority. */
public record ReviewerExecutionReference(
        WorkItemScope scope,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        AgentProfileId agentProfileId,
        long agentProfileVersion,
        PrincipalId agentPrincipalId,
        Optional<TeamMemberId> reviewerOwnerMemberId,
        Optional<TeamMemberId> subjectOwnerMemberId,
        ReviewerRelationship relationship,
        AgentTemplateVersion templateVersion,
        AgentTemplateHash templateHash,
        AgentConfigurationRevision configurationRevision,
        AgentConfigurationHash configurationHash,
        PolicySnapshotId policySnapshotId,
        long policySnapshotRevision,
        TaskFactHash policySnapshotHash) {

    public ReviewerExecutionReference {
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0 || policySnapshotRevision < 1) {
            throw new DomainValidationException(
                    "reviewerExecutionReference.version", "contains an invalid version");
        }
        agentPrincipalId = Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        reviewerOwnerMemberId = Objects.requireNonNull(
                reviewerOwnerMemberId, "reviewerOwnerMemberId");
        subjectOwnerMemberId = Objects.requireNonNull(
                subjectOwnerMemberId, "subjectOwnerMemberId");
        relationship = Objects.requireNonNull(relationship, "relationship");
        ReviewerRelationship expected = reviewerOwnerMemberId.isPresent()
                        && reviewerOwnerMemberId.equals(subjectOwnerMemberId)
                ? ReviewerRelationship.SELF_REVIEW
                : ReviewerRelationship.INDEPENDENT;
        if (relationship != expected) {
            throw new DomainValidationException(
                    "reviewerExecutionReference.relationship", "must be server-derived from owners");
        }
        templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
        if (!"reviewer".equals(templateVersion.key().value())) {
            throw new DomainValidationException(
                    "reviewerExecutionReference.templateVersion",
                    "must identify the reviewer template");
        }
        templateHash = Objects.requireNonNull(templateHash, "templateHash");
        configurationRevision = Objects.requireNonNull(
                configurationRevision, "configurationRevision");
        configurationHash = Objects.requireNonNull(configurationHash, "configurationHash");
        policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
    }

    /** Captures only a Schema v2 PolicySnapshot; legacy policy cannot authorize M5 Review. */
    public static ReviewerExecutionReference capture(
            PolicySnapshot policySnapshot, Optional<TeamMemberId> subjectOwnerMemberId) {
        PolicySnapshot policy = Objects.requireNonNull(policySnapshot, "policySnapshot");
        ResolvedAgentExecutionConfiguration execution = policy.agentExecutionConfiguration()
                .orElseThrow(() -> new DomainValidationException(
                        "reviewerExecutionReference.policySnapshot",
                        "must use PolicySnapshot Schema v2"));
        Optional<TeamMemberId> reviewerOwner = execution.ownership().ownerMemberId();
        Optional<TeamMemberId> subjectOwner = Objects.requireNonNull(
                subjectOwnerMemberId, "subjectOwnerMemberId");
        return new ReviewerExecutionReference(
                policy.scope(),
                policy.taskId(),
                policy.executionId(),
                execution.agentProfileId(),
                execution.agentProfileVersion(),
                execution.agentPrincipalId(),
                reviewerOwner,
                subjectOwner,
                reviewerOwner.isPresent() && reviewerOwner.equals(subjectOwner)
                        ? ReviewerRelationship.SELF_REVIEW
                        : ReviewerRelationship.INDEPENDENT,
                execution.templateVersion(),
                execution.templateContentHash(),
                execution.configurationRevision(),
                execution.configurationHash(),
                policy.id(),
                policy.revision(),
                policy.snapshotHash());
    }
}
