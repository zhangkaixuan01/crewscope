package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingCandidate;
import io.crewscope.domain.review.ReviewFindingFingerprint;
import io.crewscope.domain.review.ReviewFindingId;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewFindingObservationId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewTestEvidenceReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerMode;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M5-I06 verifies application deduplication, replay recovery and bounded repair summaries. */
class ReviewFindingBatchRecorderM5I06Test {

    @Test
    void retainsOneFindingAndAppendsObservationsAcrossDuplicateOutputAndResume() {
        Fixture fixture = new Fixture(false);
        ReviewFindingCandidate first = fixture.candidate("Calling strip on a null name throws");
        ReviewFindingCandidate equivalent = fixture.candidate(
                "  CALLING   STRIP ON A NULL NAME THROWS  ");

        ReviewFindingBatchResult initial = fixture.recorder.record(
                fixture.request, fixture.context, List.of(first, equivalent),
                fixture.request.version(), fixture.reviewerAgent, Fixture.LATER);
        ReviewFindingBatchResult resumed = fixture.recorder.record(
                fixture.request, fixture.context, List.of(first),
                fixture.request.version(), fixture.reviewerAgent, Fixture.LATER);

        assertEquals(1, initial.insertedFindings().size());
        assertEquals(1, initial.duplicateObservations().size());
        assertEquals(2, initial.duplicateObservations().get(0).observationNumber());
        assertEquals(0, resumed.insertedFindings().size());
        assertEquals(3, resumed.duplicateObservations().get(0).observationNumber());
        assertEquals(1, fixture.findings.values.size());
        assertEquals(2, fixture.observations.values.size());
    }

    @Test
    void derivesSelfReviewAdvisoryAndBoundsRepairSummary() {
        Fixture fixture = new Fixture(true);
        List<ReviewFindingCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            candidates.add(fixture.candidate("Distinct issue " + index));
        }

        ReviewFindingBatchResult result = fixture.recorder.record(
                fixture.request, fixture.context, candidates,
                fixture.request.version(), fixture.reviewerAgent, Fixture.LATER);

        assertEquals(21, result.insertedFindings().size());
        assertTrue(result.effectiveFindings().stream()
                .allMatch(finding -> finding.reviewerMode() == ReviewerMode.ADVISORY));
        assertTrue(result.effectiveFindings().stream()
                .allMatch(finding -> finding.reviewerRelationship()
                        == ReviewerRelationship.SELF_REVIEW));
        assertEquals(20, result.repairSummary().findings().size());
        assertTrue(result.repairSummary().truncated());
    }

    @Test
    void rejectsEvidenceOutsideTheCurrentHashClosedContext() {
        Fixture fixture = new Fixture(false);
        ReviewFindingCandidate candidate = fixture.candidate("Invalid authority");
        FindingEvidence original = candidate.evidence().get(0);
        ReviewFindingCandidate stale = new ReviewFindingCandidate(
                candidate.severity(), candidate.category(), candidate.title(), candidate.claim(),
                candidate.suggestedFix(), List.of(new FindingEvidence(
                        original.location(), original.diffArtifact(), original.diffManifestHash(),
                        original.testEvidenceId(), TaskFactHash.sha256("stale-test"),
                        original.acceptanceCriterionIndex())));

        assertThrows(DomainValidationException.class, () -> fixture.recorder.record(
                fixture.request, fixture.context, List.of(stale),
                fixture.request.version(), fixture.reviewerAgent, Fixture.LATER));
        assertTrue(fixture.findings.values.isEmpty());
    }

    private static final class Fixture {
        private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-23T05:00:00Z");
        private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-23T05:01:00Z");
        private static final String PATH = "src/main/java/io/crewscope/Greeting.java";
        private static final String PATCH = "+return name.strip();\n";

        private final WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                WorkProjectId.generate());
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final Principal actor = principal(PrincipalType.USER, "Review owner", Optional.empty());
        private final Principal reviewerAgent = principal(
                PrincipalType.SPECIALIST_AGENT, "Reviewer Specialist", Optional.of(actor.id()));
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("target"));
        private final CommandEvidenceReference command = new CommandEvidenceReference(
                CommandEvidenceId.generate(), EvidenceSequence.first(),
                TaskFactHash.sha256("command"), Optional.empty());
        private final ReviewDiffReference diff = new ReviewDiffReference(
                scope, taskId, executionId, 1,
                new DiffArtifactReference(DiffArtifactId.generate(), TaskFactHash.sha256("diff")),
                target, new RepositoryCommitId("a".repeat(40)),
                new RepositoryCommitId("b".repeat(40)), DiffGeneration.first(),
                RuntimeContentHash.sha256("manifest"),
                new PatchArtifactReference(
                        ArtifactId.generate(), PATCH.getBytes(StandardCharsets.UTF_8).length,
                        RuntimeContentHash.sha256(PATCH)),
                List.of(new DiffPath(PATH)));
        private final ReviewTestEvidenceReference test = new ReviewTestEvidenceReference(
                scope, taskId, executionId, 1, target, TestEvidenceId.generate(),
                TaskFactHash.sha256("test"), diff.generation(), diff.manifestHash(),
                List.of(new ReviewCommandEvidenceReference(
                        command, CommandKind.TEST, CommandTermination.EXITED, Optional.of(0),
                        new EvidenceSummary("Tests completed"))),
                List.of(new AcceptanceResult(
                        1, "Handle null name", AcceptanceStatus.FAILED, List.of(command),
                        new EvidenceSummary("Null input failed"))));
        private final ReviewerExecutionReference reviewer;
        private final ReviewSubject subject;
        private final ContextPackage context;
        private final ReviewRequest request;
        private final MemoryFindings findings = new MemoryFindings();
        private final MemoryObservations observations = new MemoryObservations();
        private final ReviewFindingBatchRecorder recorder =
                new ReviewFindingBatchRecorder(findings, observations);

        private Fixture(boolean selfReview) {
            TeamMemberId reviewerOwner = TeamMemberId.generate();
            Optional<TeamMemberId> subjectOwner = Optional.of(
                    selfReview ? reviewerOwner : TeamMemberId.generate());
            reviewer = new ReviewerExecutionReference(
                    scope, taskId, executionId, profileId, 1, reviewerAgent.id(),
                    Optional.of(reviewerOwner), subjectOwner,
                    selfReview ? ReviewerRelationship.SELF_REVIEW
                            : ReviewerRelationship.INDEPENDENT,
                    AgentTemplateVersion.of("reviewer", 1), AgentTemplateHash.sha256("template"),
                    new AgentConfigurationRevision(1),
                    new AgentConfigurationHash(TaskFactHash.sha256("configuration").value()),
                    PolicySnapshotId.generate(), 1, TaskFactHash.sha256("policy"));
            subject = ReviewSubject.codeChange(
                    ReviewSubjectId.generate(), scope, taskId, executionId, 1, diff, actor, NOW);
            context = ContextPackage.initial(
                    ContextPackageId.generate(), subject, diff, test,
                    List.of(ReviewDiffHunk.captured(PATH, 13, 13, PATCH)), reviewer, actor, NOW);
            ReviewRequest open = ReviewRequest.initial(ReviewRequestId.generate(), context, actor, NOW);
            request = open.start(context, open.version(), actor, LATER);
        }

        private ReviewFindingCandidate candidate(String claim) {
            return new ReviewFindingCandidate(
                    FindingSeverity.HIGH, FindingCategory.CORRECTNESS,
                    "Null handling is missing", claim, "Guard null before trimming",
                    List.of(new FindingEvidence(
                            new FindingLocation(new DiffPath(PATH), 13, 13), diff.artifact(),
                            diff.manifestHash(), test.id(), test.evidenceHash(), 1)));
        }

        private Principal principal(
                PrincipalType type, String name, Optional<PrincipalId> owner) {
            return Principal.create(
                    PrincipalId.generate(), PrincipalScope.team(scope.organizationId(), scope.teamId()),
                    type, owner, name, Optional.empty(), PrincipalVisibility.TEAM, NOW);
        }
    }

    private static final class MemoryFindings implements ReviewFindingRepository {
        private final Map<ReviewFindingId, ReviewFinding> values = new LinkedHashMap<>();

        @Override
        public Optional<ReviewFinding> findById(
                OrganizationId organizationId, ReviewFindingId id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public Optional<ReviewFinding> findByRequestAndFingerprint(
                OrganizationId organizationId,
                ReviewRequestId reviewRequestId,
                ReviewFindingFingerprint fingerprint) {
            return values.values().stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.reviewRequest().id().equals(reviewRequestId))
                    .filter(value -> value.fingerprint().equals(fingerprint))
                    .findFirst();
        }

        @Override
        public List<ReviewFinding> findAllByRequest(
                OrganizationId organizationId, ReviewRequestId reviewRequestId) {
            return values.values().stream()
                    .filter(value -> value.scope().organizationId().equals(organizationId))
                    .filter(value -> value.reviewRequest().id().equals(reviewRequestId))
                    .toList();
        }

        @Override
        public void insert(ReviewFinding finding) {
            values.put(finding.id(), finding);
        }
    }

    private static final class MemoryObservations implements ReviewFindingObservationRepository {
        private final Map<ReviewFindingObservationId, ReviewFindingObservation> values =
                new LinkedHashMap<>();

        @Override
        public Optional<ReviewFindingObservation> findById(
                OrganizationId organizationId, ReviewFindingObservationId id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public List<ReviewFindingObservation> findAllByFinding(
                OrganizationId organizationId, ReviewFindingId findingId) {
            return values.values().stream()
                    .filter(value -> value.finding().id().equals(findingId))
                    .toList();
        }

        @Override
        public void insert(ReviewFindingObservation observation) {
            values.put(observation.id(), observation);
        }
    }
}
