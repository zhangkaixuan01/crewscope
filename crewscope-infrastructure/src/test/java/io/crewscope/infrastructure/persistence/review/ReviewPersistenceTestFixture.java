package io.crewscope.infrastructure.persistence.review;

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
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.FindingCategory;
import io.crewscope.domain.review.FindingEvidence;
import io.crewscope.domain.review.FindingLocation;
import io.crewscope.domain.review.FindingSeverity;
import io.crewscope.domain.review.ReviewCommandEvidenceReference;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewFindingCandidate;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Deterministic M5-I07 domain graph used by PostgreSQL adapter tests. */
final class ReviewPersistenceTestFixture {

    static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-23T01:00:00Z");
    static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-23T02:00:00Z");

    final WorkItemScope scope = new WorkItemScope(
            OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
            WorkProjectId.generate());
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
    final Principal reviewerAgent = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.team(scope.organizationId(), scope.teamId()),
            PrincipalType.SPECIALIST_AGENT,
            Optional.of(actor.id()),
            "Reviewer Specialist",
            Optional.empty(),
            PrincipalVisibility.TEAM,
            CREATED_AT);
    final CodingTargetSnapshotReference codingTarget = new CodingTargetSnapshotReference(
            CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
    final CommandEvidenceReference command = new CommandEvidenceReference(
            CommandEvidenceId.generate(), EvidenceSequence.first(),
            TaskFactHash.sha256("command"), Optional.empty());
    final ReviewDiffReference diff = diff("diff", 1);
    final ReviewTestEvidenceReference testEvidence = testEvidence();
    final ReviewerExecutionReference reviewer = reviewer();
    final ReviewSubject subject = ReviewSubject.codeChange(
            ReviewSubjectId.generate(), scope, taskId, executionId, 1, diff, actor, CREATED_AT);
    final ContextPackage context = ContextPackage.initial(
            ContextPackageId.generate(),
            subject,
            diff,
            testEvidence,
            List.of(ReviewDiffHunk.captured(
                    "src/main/java/io/crewscope/Greeting.java",
                    14,
                    16,
                    "+return name == null ? \"\" : name.strip();\n")),
            reviewer,
            actor,
            CREATED_AT);
    final ReviewRequest runningRequest = ReviewRequest.initial(
                    ReviewRequestId.generate(), context, actor, CREATED_AT)
            .start(context, 0, actor, LATER);

    ReviewFindingCandidate finding(String title, FindingSeverity severity) {
        FindingEvidence evidence = new FindingEvidence(
                new FindingLocation(
                        "src/main/java/io/crewscope/Greeting.java", 14, 14),
                diff.artifact(),
                diff.manifestHash(),
                testEvidence.id(),
                testEvidence.evidenceHash(),
                1);
        return new ReviewFindingCandidate(
                severity,
                FindingCategory.CORRECTNESS,
                title,
                "The null branch returns a value inconsistent with the contract",
                "Return the documented empty value and retain the regression test",
                List.of(evidence));
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
                new RepositoryCommitId("b".repeat(40)),
                new DiffGeneration(generation),
                RuntimeContentHash.sha256("manifest-" + salt),
                new PatchArtifactReference(
                        ArtifactId.generate(),
                        patch.getBytes(StandardCharsets.UTF_8).length,
                        RuntimeContentHash.sha256(patch)),
                List.of(new DiffPath("src/main/java/io/crewscope/Greeting.java")));
    }

    private ReviewTestEvidenceReference testEvidence() {
        ReviewCommandEvidenceReference reviewCommand = new ReviewCommandEvidenceReference(
                command,
                CommandKind.TEST,
                CommandTermination.EXITED,
                Optional.of(0),
                new EvidenceSummary("All tests passed"));
        AcceptanceResult acceptance = new AcceptanceResult(
                1,
                "Return an empty value when name is null",
                AcceptanceStatus.PASSED,
                List.of(command),
                new EvidenceSummary("Criterion passed"));
        return new ReviewTestEvidenceReference(
                scope,
                taskId,
                executionId,
                1,
                codingTarget,
                TestEvidenceId.generate(),
                TaskFactHash.sha256("test"),
                diff.generation(),
                diff.manifestHash(),
                List.of(reviewCommand),
                List.of(acceptance));
    }

    private ReviewerExecutionReference reviewer() {
        TeamMemberId reviewerOwner = TeamMemberId.generate();
        TeamMemberId subjectOwner = TeamMemberId.generate();
        return new ReviewerExecutionReference(
                scope,
                taskId,
                executionId,
                AgentProfileId.generate(),
                1,
                reviewerAgent.id(),
                Optional.of(reviewerOwner),
                Optional.of(subjectOwner),
                ReviewerRelationship.INDEPENDENT,
                AgentTemplateVersion.of("reviewer", 1),
                AgentTemplateHash.sha256("reviewer-template"),
                new AgentConfigurationRevision(1),
                new AgentConfigurationHash(TaskFactHash.sha256("configuration").value()),
                PolicySnapshotId.generate(),
                1,
                TaskFactHash.sha256("policy"));
    }
}
