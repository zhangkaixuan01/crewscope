package io.crewscope.domain.review;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.AcceptanceStatus;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.CommandEvidenceId;
import io.crewscope.domain.coding.CommandEvidenceReference;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.CommandTermination;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.EvidenceSummary;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;

final class ReviewDomainFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-23T01:00:00Z");
    static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-23T02:00:00Z");

    final WorkItemScope scope = new WorkItemScope(
            OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
    final TaskId taskId = TaskId.generate();
    final TaskExecutionId executionId = TaskExecutionId.generate();
    final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(scope.organizationId(), scope.teamId()),
            PrincipalType.USER,
            Optional.empty(),
            "Review owner",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            CREATED_AT);
    final TeamMemberId reviewerOwner = TeamMemberId.generate();
    final TeamMemberId subjectOwner = TeamMemberId.generate();
    final AgentProfileId reviewerProfileId = AgentProfileId.generate();
    final PrincipalId reviewerPrincipalId = PrincipalId.generate();
    final Principal reviewerAgent = Principal.create(
            reviewerPrincipalId,
            PrincipalScope.team(scope.organizationId(), scope.teamId()),
            PrincipalType.SPECIALIST_AGENT,
            Optional.of(actor.id()),
            "Reviewer Specialist",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            CREATED_AT);
    final PolicySnapshotId reviewerPolicySnapshotId = PolicySnapshotId.generate();
    final CodingTargetSnapshotReference codingTarget = new CodingTargetSnapshotReference(
            CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target-1"));
    final CommandEvidenceReference command = new CommandEvidenceReference(
            CommandEvidenceId.generate(), EvidenceSequence.first(), TaskFactHash.sha256("command-1"), Optional.empty());
    final ReviewDiffReference diff = diff("diff-1", 1);
    final ReviewTestEvidenceReference testEvidence = test("test-1", diff, command);
    final ReviewerExecutionReference reviewer = reviewer("config-1", "policy-1", 1);
    final ReviewSubject subject = subject(ReviewSubjectId.generate(), diff);
    final ContextPackage context = context(ContextPackageId.generate(), subject, diff, testEvidence, reviewer);

    ReviewSubject subject(ReviewSubjectId id, ReviewDiffReference authority) {
        return ReviewSubject.codeChange(
                id, scope, taskId, executionId, 1, authority, actor, CREATED_AT);
    }

    ReviewDiffReference diff(String salt, long generation) {
        String patch = "+return name == null ? \"\" : name.strip();\n";
        return new ReviewDiffReference(
                scope,
                taskId,
                executionId,
                1,
                new DiffArtifactReference(DiffArtifactId.generate(), TaskFactHash.sha256(salt)),
                codingTarget,
                new RepositoryCommitId("a".repeat(40)),
                new RepositoryCommitId((generation == 1 ? "b" : "c").repeat(40)),
                new DiffGeneration(generation),
                RuntimeContentHash.sha256("manifest-" + salt),
                new PatchArtifactReference(
                        ArtifactId.generate(),
                        patch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        RuntimeContentHash.sha256(patch)),
                List.of(new DiffPath("src/main/java/io/crewscope/Greeting.java")));
    }

    ReviewTestEvidenceReference test(
            String salt, ReviewDiffReference authority, CommandEvidenceReference commandReference) {
        ReviewCommandEvidenceReference reviewCommand = new ReviewCommandEvidenceReference(
                commandReference,
                CommandKind.TEST,
                CommandTermination.EXITED,
                Optional.of(0),
                new EvidenceSummary("All tests passed"));
        AcceptanceResult acceptance = new AcceptanceResult(
                1,
                "Return an empty value when the input name is null",
                AcceptanceStatus.PASSED,
                List.of(commandReference),
                new EvidenceSummary("Criterion passed"));
        return new ReviewTestEvidenceReference(
                scope,
                taskId,
                executionId,
                1,
                codingTarget,
                TestEvidenceId.generate(),
                TaskFactHash.sha256(salt),
                authority.generation(),
                authority.manifestHash(),
                List.of(reviewCommand),
                List.of(acceptance));
    }

    ReviewerExecutionReference reviewer(String configurationSalt, String policySalt, long policyRevision) {
        return new ReviewerExecutionReference(
                scope,
                taskId,
                executionId,
                reviewerProfileId,
                1,
                reviewerPrincipalId,
                Optional.of(reviewerOwner),
                Optional.of(subjectOwner),
                ReviewerRelationship.INDEPENDENT,
                AgentTemplateVersion.of("reviewer", 1),
                AgentTemplateHash.sha256("reviewer-template-1"),
                new AgentConfigurationRevision(1),
                new AgentConfigurationHash(TaskFactHash.sha256(configurationSalt).value()),
                reviewerPolicySnapshotId,
                policyRevision,
                TaskFactHash.sha256(policySalt));
    }

    ContextPackage context(
            ContextPackageId id,
            ReviewSubject reviewSubject,
            ReviewDiffReference authority,
            ReviewTestEvidenceReference evidence,
            ReviewerExecutionReference execution) {
        return ContextPackage.initial(
                id,
                reviewSubject,
                authority,
                evidence,
                List.of(ReviewDiffHunk.captured(
                        "src/main/java/io/crewscope/Greeting.java",
                        14,
                        14,
                        "+return name == null ? \"\" : name.strip();\n")),
                execution,
                actor,
                CREATED_AT);
    }

    ContextPackage successor(
            ContextPackage parent,
            ReviewSubject nextSubject,
            ReviewDiffReference nextDiff,
            ReviewTestEvidenceReference nextEvidence,
            ReviewerExecutionReference nextReviewer,
            String patch) {
        return ContextPackage.successor(
                ContextPackageId.generate(),
                parent,
                nextSubject,
                nextDiff,
                nextEvidence,
                List.of(ReviewDiffHunk.captured(
                        "src/main/java/io/crewscope/Greeting.java", 14, 14, patch)),
                nextReviewer,
                actor,
                LATER);
    }
}
