package io.crewscope.infrastructure.persistence.review;

import io.crewscope.application.review.ContextPackageRepository;
import io.crewscope.application.review.ReviewDecisionRepository;
import io.crewscope.application.review.ReviewFindingObservationRepository;
import io.crewscope.application.review.ReviewFindingRepository;
import io.crewscope.application.review.ReviewModificationRoundRepository;
import io.crewscope.application.review.ReviewRequestRepository;
import io.crewscope.application.review.ReviewSubjectRepository;
import io.crewscope.domain.coding.AcceptanceResult;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidenceId;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.ReviewerEligibilityMode;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.FindingCategory;
import io.crewscope.domain.review.FindingEvidence;
import io.crewscope.domain.review.FindingLocation;
import io.crewscope.domain.review.FindingSeverity;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionReference;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewDiffHunk;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingCandidate;
import io.crewscope.domain.review.ReviewFindingFingerprint;
import io.crewscope.domain.review.ReviewFindingId;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewFindingObservationId;
import io.crewscope.domain.review.ReviewFindingReference;
import io.crewscope.domain.review.ReviewInvalidationReason;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewModificationRoundId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestReference;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewSubjectReference;
import io.crewscope.domain.review.ReviewSubjectType;
import io.crewscope.domain.review.ReviewerMode;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL Review aggregate store with explicit tenant predicates and hash-verified recovery. */
@Repository
public class JdbcReviewRepositoryAdapter implements
        ReviewSubjectRepository,
        ContextPackageRepository,
        ReviewRequestRepository,
        ReviewFindingRepository,
        ReviewFindingObservationRepository,
        ReviewDecisionRepository,
        ReviewModificationRoundRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ReviewContextAuthorityJsonCodec contextCodec;
    private final JdbcReviewQueryRepositoryAdapter projections;

    public JdbcReviewRepositoryAdapter(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ReviewContextAuthorityJsonCodec contextCodec,
            JdbcReviewQueryRepositoryAdapter projections) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.contextCodec = Objects.requireNonNull(contextCodec, "contextCodec");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    @Override
    public Optional<ReviewSubject> findById(
            OrganizationId organizationId, ReviewSubjectId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.review_subject WHERE organization_id = ? AND id = ?",
                this::subject,
                organizationId.value(),
                id.value()));
    }

    @Override
    @Transactional
    public void save(ReviewSubject subject) {
        ReviewSubject value = Objects.requireNonNull(subject, "subject");
        ReviewDiffReference diff = value.diff();
        WorkItemScope scope = value.scope();
        jdbc.update(
                """
                INSERT INTO crewscope.review_subject (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, subject_type,
                    diff_artifact_id, diff_final_hash,
                    coding_target_snapshot_id, coding_target_revision, coding_target_hash,
                    baseline_commit, delivery_commit, diff_generation, diff_manifest_hash,
                    patch_artifact_id, patch_size_bytes, patch_sha256, changed_paths,
                    subject_hash, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS JSONB), ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                value.id().value(),
                scope.organizationId().value(), scope.teamId().value(),
                scope.workspaceId().value(), scope.projectId().value(),
                value.taskId().value(), value.taskExecutionId().value(), value.attempt(),
                value.type().name(), diff.artifact().id().value(), diff.artifact().finalHash().value(),
                diff.codingTarget().snapshotId().value(), diff.codingTarget().revision(),
                diff.codingTarget().snapshotHash().value(), diff.baselineCommit().value(),
                diff.deliveryCommit().value(), diff.generation().value(), diff.manifestHash().value(),
                diff.patchArtifact().artifactId().value(), diff.patchArtifact().sizeBytes(),
                diff.patchArtifact().patchSha256().value(),
                json(diff.changedPaths().stream().map(DiffPath::value).toList()),
                value.subjectHash().value(), time(value.audit().createdAt()), creator(value.audit()));
        ReviewSubject committed = findById(scope.organizationId(), value.id()).orElseThrow();
        if (!committed.subjectHash().equals(value.subjectHash())) {
            throw new IllegalStateException("ReviewSubject ID already contains different authority");
        }
    }

    @Override
    public Optional<ContextPackage> findById(
            OrganizationId organizationId, ContextPackageId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.review_context_package WHERE organization_id = ? AND id = ?",
                this::context,
                organizationId.value(),
                id.value()));
    }

    @Override
    public Optional<ContextPackage> findLatestByExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId, int attempt) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.review_context_package
                WHERE organization_id = ? AND task_execution_id = ? AND attempt = ?
                ORDER BY package_version DESC, id DESC LIMIT 1
                """,
                this::context,
                organizationId.value(), taskExecutionId.value(), attempt));
    }

    @Override
    @Transactional
    public void save(ContextPackage context) {
        ContextPackage value = Objects.requireNonNull(context, "context");
        WorkItemScope scope = value.scope();
        var reviewer = value.reviewer();
        jdbc.update(
                """
                INSERT INTO crewscope.review_context_package (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, package_version, parent_package_id,
                    subject_id, subject_type, subject_hash,
                    diff_artifact_id, diff_final_hash,
                    coding_target_snapshot_id, coding_target_revision, coding_target_hash,
                    diff_generation, diff_manifest_hash, test_evidence_id, test_evidence_hash,
                    reviewer_agent_profile_id, reviewer_agent_profile_version,
                    reviewer_agent_principal_id, reviewer_owner_member_id,
                    subject_owner_member_id, reviewer_relationship,
                    reviewer_template_key, reviewer_template_version, reviewer_template_hash,
                    reviewer_configuration_revision, reviewer_configuration_hash,
                    policy_snapshot_id, policy_snapshot_revision, policy_snapshot_hash,
                    context_hash, authority_snapshot, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
                """,
                value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                scope.workspaceId().value(), scope.projectId().value(), value.taskId().value(),
                value.taskExecutionId().value(), value.attempt(), value.version(),
                value.parentPackageId().map(ContextPackageId::value).orElse(null),
                value.subject().id().value(), value.subject().type().name(),
                value.subject().subjectHash().value(), value.diff().artifact().id().value(),
                value.diff().artifact().finalHash().value(),
                value.diff().codingTarget().snapshotId().value(),
                value.diff().codingTarget().revision(),
                value.diff().codingTarget().snapshotHash().value(),
                value.diff().generation().value(), value.diff().manifestHash().value(),
                value.testEvidence().id().value(), value.testEvidence().evidenceHash().value(),
                reviewer.agentProfileId().value(), reviewer.agentProfileVersion(),
                reviewer.agentPrincipalId().value(),
                reviewer.reviewerOwnerMemberId().map(TeamMemberId::value).orElse(null),
                reviewer.subjectOwnerMemberId().map(TeamMemberId::value).orElse(null),
                reviewer.relationship().name(), reviewer.templateVersion().key().value(),
                reviewer.templateVersion().version(), reviewer.templateHash().value(),
                reviewer.configurationRevision().value(), reviewer.configurationHash().value(),
                reviewer.policySnapshotId().value(), reviewer.policySnapshotRevision(),
                reviewer.policySnapshotHash().value(), value.contextHash().value(),
                contextCodec.encode(value), time(value.audit().createdAt()), creator(value.audit()));
        insertContextChildren(value);
    }

    @Override
    public Optional<ReviewRequest> findById(
            OrganizationId organizationId, ReviewRequestId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.review_request WHERE organization_id = ? AND id = ?",
                this::request,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ReviewRequest> findCurrentByExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId, int attempt) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.review_request
                WHERE organization_id = ? AND task_execution_id = ? AND attempt = ?
                ORDER BY revision DESC, id DESC LIMIT 1
                """,
                this::request,
                organizationId.value(), taskExecutionId.value(), attempt));
    }

    @Override
    @Transactional
    public void insert(ReviewRequest request) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        WorkItemScope scope = value.scope();
        try {
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_request (
                        id, organization_id, team_id, workspace_id, project_id,
                        task_id, task_execution_id, attempt, revision, predecessor_request_id,
                        subject_id, subject_type, subject_hash,
                        context_package_id, context_package_version, context_hash,
                        request_hash, status, invalidation_reason, version,
                        created_at, created_by_principal_id, updated_at, updated_by_principal_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                    scope.workspaceId().value(), scope.projectId().value(), value.taskId().value(),
                    value.taskExecutionId().value(), value.attempt(), value.revision(),
                    value.predecessorRequestId().map(ReviewRequestId::value).orElse(null),
                    value.subject().id().value(), value.subject().type().name(),
                    value.subject().subjectHash().value(), value.contextPackage().id().value(),
                    value.contextPackage().version(), value.contextPackage().contextHash().value(),
                    value.requestHash().value(), value.status().name(),
                    value.invalidationReason().map(Enum::name).orElse(null), value.version(),
                    time(value.audit().createdAt()), creator(value.audit()),
                    time(value.audit().updatedAt()), updater(value.audit()));
        } catch (DataIntegrityViolationException failure) {
            throw ReviewPersistenceConflictMapper.reviewRequest(failure);
        }
        projections.rebuild(scope.organizationId(), value.id());
    }

    @Override
    @Transactional
    public void update(ReviewRequest request, long expectedVersion) {
        ReviewRequest value = Objects.requireNonNull(request, "request");
        int updated = jdbc.update(
                """
                UPDATE crewscope.review_request
                SET status = ?, invalidation_reason = ?, version = ?,
                    updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """,
                value.status().name(), value.invalidationReason().map(Enum::name).orElse(null),
                value.version(), time(value.audit().updatedAt()), updater(value.audit()),
                value.scope().organizationId().value(), value.id().value(), expectedVersion);
        if (updated != 1) {
            long actual = jdbc.queryForObject(
                    "SELECT version FROM crewscope.review_request WHERE organization_id = ? AND id = ?",
                    Long.class, value.scope().organizationId().value(), value.id().value());
            throw new OptimisticLockConflictException(
                    "ReviewRequest", value.id(), expectedVersion, actual);
        }
        projections.rebuild(value.scope().organizationId(), value.id());
    }

    @Override
    public Optional<ReviewFinding> findById(
            OrganizationId organizationId, ReviewFindingId id) {
        return one(jdbc.query(
                findingSelect("f.organization_id = ? AND f.id = ?"),
                this::finding,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ReviewFinding> findByRequestAndFingerprint(
            OrganizationId organizationId,
            ReviewRequestId reviewRequestId,
            ReviewFindingFingerprint fingerprint) {
        return one(jdbc.query(
                findingSelect("f.organization_id = ? AND f.review_request_id = ? AND f.fingerprint = ?"),
                this::finding,
                organizationId.value(), reviewRequestId.value(), fingerprint.toString()));
    }

    @Override
    public List<ReviewFinding> findAllByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId) {
        return jdbc.query(
                findingSelect("f.organization_id = ? AND f.review_request_id = ?")
                        + " ORDER BY f.created_at, f.id",
                this::finding,
                organizationId.value(), reviewRequestId.value());
    }

    @Override
    @Transactional
    public void insert(ReviewFinding finding) {
        insertFinding(Objects.requireNonNull(finding, "finding"), false);
    }

    @Override
    @Transactional
    public ReviewFinding insertOrFind(ReviewFinding finding) {
        ReviewFinding value = Objects.requireNonNull(finding, "finding");
        if (insertFinding(value, true)) {
            return value;
        }
        return findByRequestAndFingerprint(
                        value.scope().organizationId(),
                        value.reviewRequest().id(),
                        value.fingerprint())
                .orElseThrow(() -> new IllegalStateException(
                        "Concurrent Finding winner disappeared"));
    }

    @Override
    public Optional<ReviewFindingObservation> findById(
            OrganizationId organizationId, ReviewFindingObservationId id) {
        return one(jdbc.query(
                observationSelect("o.organization_id = ? AND o.id = ?"),
                this::observation,
                organizationId.value(), id.value()));
    }

    @Override
    public List<ReviewFindingObservation> findAllByFinding(
            OrganizationId organizationId, ReviewFindingId findingId) {
        return jdbc.query(
                observationSelect(
                        "o.organization_id = ? AND o.review_finding_id = ?")
                        + " ORDER BY o.observation_number, o.id",
                this::observation,
                organizationId.value(), findingId.value());
    }

    @Override
    @Transactional
    public void insert(ReviewFindingObservation observation) {
        insertObservation(Objects.requireNonNull(observation, "observation"));
    }

    @Override
    @Transactional
    public ReviewFindingObservation append(ReviewFindingObservation observation) {
        ReviewFindingObservation proposed = Objects.requireNonNull(observation, "observation");
        OrganizationId organizationId = proposed.finding().reviewRequest().scope().organizationId();
        jdbc.queryForObject(
                "SELECT id FROM crewscope.review_finding WHERE organization_id = ? AND id = ? FOR UPDATE",
                UUID.class,
                organizationId.value(),
                proposed.finding().id().value());
        Long maximum = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(observation_number), 1)
                FROM crewscope.review_finding_observation
                WHERE organization_id = ? AND review_finding_id = ?
                """,
                Long.class,
                organizationId.value(),
                proposed.finding().id().value());
        long number = Math.addExact(maximum, 1L);
        ReviewFindingObservation committed = new ReviewFindingObservation(
                proposed.id(), proposed.finding(), number, proposed.candidateHash(),
                proposed.reviewerPrincipalId(), proposed.audit());
        insertObservation(committed);
        return committed;
    }

    @Override
    public Optional<ReviewDecision> findById(
            OrganizationId organizationId, ReviewDecisionId id) {
        return one(jdbc.query(
                decisionSelect("d.organization_id = ? AND d.id = ?"),
                this::decision,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ReviewDecision> findLatestByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId) {
        return one(jdbc.query(
                decisionSelect("d.organization_id = ? AND d.review_request_id = ?")
                        + " ORDER BY d.revision DESC, d.id DESC LIMIT 1",
                this::decision,
                organizationId.value(), reviewRequestId.value()));
    }

    @Override
    public List<ReviewDecision> findDecisionsByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId) {
        return jdbc.query(
                decisionSelect("d.organization_id = ? AND d.review_request_id = ?")
                        + " ORDER BY d.revision, d.id",
                this::decision,
                organizationId.value(), reviewRequestId.value());
    }

    @Override
    @Transactional
    public void insert(ReviewDecision decision) {
        ReviewDecision value = Objects.requireNonNull(decision, "decision");
        WorkItemScope scope = value.scope();
        ReviewerEligibilityDecision eligibility = value.eligibility();
        String databaseMode = eligibility.mode() == ReviewerEligibilityMode.STRICT_SEPARATION
                ? "INDEPENDENT_MEMBER"
                : "EXPLICIT_SELF_REVIEW_OVERRIDE";
        String reason = eligibility.overrideReason().orElse("strict-separation");
        jdbc.update(
                """
                INSERT INTO crewscope.review_decision (
                    id, organization_id, team_id, workspace_id, project_id, work_item_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    revision, predecessor_decision_id, reviewer_mode,
                    reviewer_principal_id, reviewer_member_id,
                    eligibility_mode, eligibility_reason,
                    eligibility_conflicting_roles, eligibility_policy_pack_id,
                    eligibility_policy_pack_version, eligibility_override_reason,
                    decision_type, rationale, decision_hash, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS JSONB), ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                scope.workspaceId().value(), scope.projectId().value(), value.workItemId().value(),
                value.taskId().value(), value.reviewRequest().taskExecutionId().value(),
                value.reviewRequest().attempt(), value.reviewRequest().id().value(),
                value.reviewRequest().revision(), value.reviewRequest().version(),
                value.reviewRequest().requestHash().value(), value.revision(),
                value.predecessorDecisionId().map(ReviewDecisionId::value).orElse(null),
                value.reviewerMode().name(), value.reviewerPrincipalId().value(),
                value.reviewerMemberId().value(), databaseMode,
                reason.length() <= 128 ? reason : reason.substring(0, 128),
                json(eligibility.conflictingRoles().stream().map(Enum::name).sorted().toList()),
                eligibility.policyPack().map(reference -> reference.id().value()).orElse(null),
                eligibility.policyPack().map(PolicyPackReference::version).orElse(null),
                eligibility.overrideReason().orElse(null), value.type().name(), value.rationale(),
                value.decisionHash().value(), time(value.audit().createdAt()), creator(value.audit()));
        projections.rebuild(scope.organizationId(), value.reviewRequest().id());
    }

    @Override
    public Optional<ReviewModificationRound> findById(
            OrganizationId organizationId, ReviewModificationRoundId id) {
        return one(jdbc.query(
                roundSelect("m.organization_id = ? AND m.id = ?"),
                this::round,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ReviewModificationRound> findLatestByTask(
            OrganizationId organizationId, TaskId taskId) {
        return one(jdbc.query(
                roundSelect("m.organization_id = ? AND m.task_id = ?")
                        + " ORDER BY m.round_number DESC, m.id DESC LIMIT 1",
                this::round,
                organizationId.value(), taskId.value()));
    }

    @Override
    public List<ReviewModificationRound> findAllByTask(
            OrganizationId organizationId, TaskId taskId) {
        return jdbc.query(
                roundSelect("m.organization_id = ? AND m.task_id = ?")
                        + " ORDER BY m.round_number, m.id",
                this::round,
                organizationId.value(), taskId.value());
    }

    @Override
    @Transactional
    public void insert(ReviewModificationRound round) {
        ReviewModificationRound value = Objects.requireNonNull(round, "round");
        WorkItemScope scope = value.scope();
        jdbc.update(
                """
                INSERT INTO crewscope.review_modification_round (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, round_number, predecessor_round_id,
                    source_request_id, source_request_revision, source_request_version,
                    source_request_hash, trigger_decision_id, trigger_decision_revision,
                    trigger_decision_type, trigger_decision_hash, round_hash,
                    created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                scope.workspaceId().value(), scope.projectId().value(), value.taskId().value(),
                value.sourceRequest().taskExecutionId().value(), value.roundNumber(),
                value.predecessorRoundId().map(ReviewModificationRoundId::value).orElse(null),
                value.sourceRequest().id().value(), value.sourceRequest().revision(),
                value.sourceRequest().version(), value.sourceRequest().requestHash().value(),
                value.triggerDecision().id().value(), value.triggerDecision().revision(),
                value.triggerDecision().type().name(), value.triggerDecision().decisionHash().value(),
                value.roundHash().value(), time(value.audit().createdAt()), creator(value.audit()));
        projections.rebuild(scope.organizationId(), value.sourceRequest().id());
    }

    private ReviewSubject subject(ResultSet row, int ignored) throws SQLException {
        WorkItemScope scope = scope(row);
        ReviewDiffReference diff = new ReviewDiffReference(
                scope,
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                new DiffArtifactReference(
                        new DiffArtifactId(uuid(row, "diff_artifact_id")),
                        new TaskFactHash(row.getString("diff_final_hash"))),
                new CodingTargetSnapshotReference(
                        new CodingTargetSnapshotId(uuid(row, "coding_target_snapshot_id")),
                        row.getLong("coding_target_revision"),
                        new TaskFactHash(row.getString("coding_target_hash"))),
                new RepositoryCommitId(row.getString("baseline_commit")),
                new RepositoryCommitId(row.getString("delivery_commit")),
                new DiffGeneration(row.getLong("diff_generation")),
                new RuntimeContentHash(row.getString("diff_manifest_hash")),
                new PatchArtifactReference(
                        new ArtifactId(uuid(row, "patch_artifact_id")),
                        row.getLong("patch_size_bytes"),
                        new RuntimeContentHash(row.getString("patch_sha256"))),
                readStringList(row.getString("changed_paths")).stream().map(DiffPath::new).toList());
        return ReviewSubject.reconstitute(
                new ReviewSubjectId(uuid(row, "id")),
                ReviewSubjectType.valueOf(row.getString("subject_type")),
                scope,
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                diff,
                new TaskFactHash(row.getString("subject_hash")),
                immutableAudit(row));
    }

    private ContextPackage context(ResultSet row, int ignored) throws SQLException {
        OrganizationId organizationId = new OrganizationId(uuid(row, "organization_id"));
        ReviewSubject subject = findById(
                        organizationId, new ReviewSubjectId(uuid(row, "subject_id")))
                .orElseThrow(() -> new IllegalStateException("ReviewSubject is missing"));
        var authority = contextCodec.decode(row.getString("authority_snapshot"), subject);
        ContextPackage result = ContextPackage.reconstitute(
                new ContextPackageId(uuid(row, "id")),
                row.getLong("package_version"),
                optionalUuid(row, "parent_package_id").map(ContextPackageId::new),
                subject,
                authority.diff(),
                authority.testEvidence(),
                authority.hunks(),
                authority.reviewer(),
                new TaskFactHash(row.getString("context_hash")),
                immutableAudit(row));
        validateContextScalars(row, result);
        validateContextChildren(result);
        return result;
    }

    private ReviewRequest request(ResultSet row, int ignored) throws SQLException {
        OrganizationId organizationId = new OrganizationId(uuid(row, "organization_id"));
        ContextPackage context = findById(
                        organizationId, new ContextPackageId(uuid(row, "context_package_id")))
                .orElseThrow(() -> new IllegalStateException("ContextPackage is missing"));
        if (context.version() != row.getLong("context_package_version")
                || !context.contextHash().value().equals(row.getString("context_hash"))) {
            throw new IllegalStateException("ReviewRequest ContextPackage authority drifted");
        }
        return ReviewRequest.reconstitute(
                new ReviewRequestId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                row.getLong("revision"),
                optionalUuid(row, "predecessor_request_id").map(ReviewRequestId::new),
                context.subject(),
                context.reference(),
                context.diff(),
                context.testEvidence(),
                context.reviewer(),
                new TaskFactHash(row.getString("request_hash")),
                ReviewRequestStatus.valueOf(row.getString("status")),
                optionalString(row, "invalidation_reason").map(ReviewInvalidationReason::valueOf),
                row.getLong("version"),
                audit(row));
    }

    private ReviewFinding finding(ResultSet row, int ignored) throws SQLException {
        ReviewRequestReference request = requestReference(row, "request_");
        ReviewFindingId id = new ReviewFindingId(uuid(row, "id"));
        List<FindingEvidence> evidence = jdbc.query(
                """
                SELECT evidence.*, subject.diff_final_hash
                FROM crewscope.review_finding_evidence evidence
                JOIN crewscope.review_finding finding
                  ON finding.id = evidence.review_finding_id
                JOIN crewscope.review_request request
                  ON request.id = finding.review_request_id
                JOIN crewscope.review_subject subject
                  ON subject.id = request.subject_id
                WHERE evidence.review_finding_id = ? ORDER BY evidence.ordinal
                """,
                this::findingEvidence,
                id.value());
        ReviewFindingCandidate candidate = new ReviewFindingCandidate(
                FindingSeverity.valueOf(row.getString("severity")),
                FindingCategory.valueOf(row.getString("category")),
                row.getString("title"), row.getString("claim"), row.getString("suggested_fix"),
                evidence);
        return ReviewFinding.reconstitute(
                id,
                scope(row),
                request,
                ReviewerMode.valueOf(row.getString("reviewer_mode")),
                ReviewerRelationship.valueOf(row.getString("reviewer_relationship")),
                new PrincipalId(uuid(row, "reviewer_principal_id")),
                candidate,
                evidence,
                new ReviewFindingFingerprint(new TaskFactHash(row.getString("fingerprint"))),
                new TaskFactHash(row.getString("candidate_hash")),
                immutableAudit(row));
    }

    private FindingEvidence findingEvidence(ResultSet row, int ignored) throws SQLException {
        return new FindingEvidence(
                new FindingLocation(
                        row.getString("path"), row.getInt("start_line"), row.getInt("end_line")),
                new DiffArtifactReference(
                        new DiffArtifactId(uuid(row, "diff_artifact_id")),
                        new TaskFactHash(row.getString("diff_final_hash"))),
                new RuntimeContentHash(row.getString("diff_manifest_hash")),
                new TestEvidenceId(uuid(row, "test_evidence_id")),
                new TaskFactHash(row.getString("test_evidence_hash")),
                row.getInt("acceptance_criterion_index"));
    }

    private ReviewFindingObservation observation(ResultSet row, int ignored) throws SQLException {
        ReviewFindingReference finding = new ReviewFindingReference(
                new ReviewFindingId(uuid(row, "review_finding_id")),
                requestReference(row, "request_"),
                new ReviewFindingFingerprint(
                        new TaskFactHash(row.getString("finding_fingerprint"))));
        return new ReviewFindingObservation(
                new ReviewFindingObservationId(uuid(row, "id")),
                finding,
                row.getLong("observation_number"),
                new TaskFactHash(row.getString("candidate_hash")),
                new PrincipalId(uuid(row, "reviewer_principal_id")),
                immutableAudit(row));
    }

    private ReviewDecision decision(ResultSet row, int ignored) throws SQLException {
        ReviewerEligibilityDecision eligibility = eligibility(row);
        return ReviewDecision.reconstitute(
                new ReviewDecisionId(uuid(row, "id")),
                scope(row),
                new WorkItemId(uuid(row, "work_item_id")),
                new TaskId(uuid(row, "task_id")),
                requestReference(row, "request_"),
                row.getLong("revision"),
                optionalUuid(row, "predecessor_decision_id").map(ReviewDecisionId::new),
                ReviewerMode.valueOf(row.getString("reviewer_mode")),
                new PrincipalId(uuid(row, "reviewer_principal_id")),
                new TeamMemberId(uuid(row, "reviewer_member_id")),
                eligibility,
                ReviewDecisionType.valueOf(row.getString("decision_type")),
                row.getString("rationale"),
                new TaskFactHash(row.getString("decision_hash")),
                immutableAudit(row));
    }

    private ReviewModificationRound round(ResultSet row, int ignored) throws SQLException {
        ReviewRequestReference request = requestReference(row, "request_");
        ReviewDecisionReference decision = new ReviewDecisionReference(
                new ReviewDecisionId(uuid(row, "trigger_decision_id")),
                row.getLong("trigger_decision_revision"),
                request,
                ReviewDecisionType.valueOf(row.getString("trigger_decision_type")),
                new TaskFactHash(row.getString("trigger_decision_hash")));
        return ReviewModificationRound.reconstitute(
                new ReviewModificationRoundId(uuid(row, "id")),
                scope(row),
                new TaskId(uuid(row, "task_id")),
                row.getLong("round_number"),
                optionalUuid(row, "predecessor_round_id").map(ReviewModificationRoundId::new),
                request,
                decision,
                new TaskFactHash(row.getString("round_hash")),
                immutableAudit(row));
    }

    private boolean insertFinding(ReviewFinding value, boolean ignoreDuplicateFingerprint) {
        WorkItemScope scope = value.scope();
        String conflict = ignoreDuplicateFingerprint
                ? " ON CONFLICT (review_request_id, fingerprint) DO NOTHING"
                : "";
        int inserted = jdbc.update(
                """
                INSERT INTO crewscope.review_finding (
                    id, organization_id, team_id, workspace_id, project_id,
                    task_id, task_execution_id, attempt, review_request_id,
                    review_request_revision, review_request_version, review_request_hash,
                    reviewer_mode, reviewer_relationship, reviewer_principal_id,
                    severity, category, title, claim, suggested_fix,
                    fingerprint, candidate_hash, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + conflict,
                value.id().value(), scope.organizationId().value(), scope.teamId().value(),
                scope.workspaceId().value(), scope.projectId().value(),
                value.reviewRequest().taskId().value(),
                value.reviewRequest().taskExecutionId().value(), value.reviewRequest().attempt(),
                value.reviewRequest().id().value(), value.reviewRequest().revision(),
                value.reviewRequest().version(), value.reviewRequest().requestHash().value(),
                value.reviewerMode().name(), value.reviewerRelationship().name(),
                value.reviewerPrincipalId().value(), value.severity().name(), value.category().name(),
                value.title(), value.claim(), value.suggestedFix(), value.fingerprint().toString(),
                value.candidateHash().value(), time(value.audit().createdAt()), creator(value.audit()));
        if (inserted == 0) {
            return false;
        }
        int ordinal = 0;
        for (FindingEvidence item : value.evidence()) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_finding_evidence (
                        review_finding_id, ordinal, path, start_line, end_line,
                        diff_artifact_id, diff_manifest_hash, test_evidence_id,
                        test_evidence_hash, acceptance_criterion_index
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.id().value(), ++ordinal, item.location().path().value(),
                    item.location().startLine(), item.location().endLine(),
                    item.diffArtifact().id().value(), item.diffManifestHash().value(),
                    item.testEvidenceId().value(), item.testEvidenceHash().value(),
                    item.acceptanceCriterionIndex());
        }
        projections.rebuild(scope.organizationId(), value.reviewRequest().id());
        return true;
    }

    private void insertObservation(ReviewFindingObservation value) {
        ReviewRequestReference request = value.finding().reviewRequest();
        jdbc.update(
                """
                INSERT INTO crewscope.review_finding_observation (
                    id, organization_id, review_request_id, review_finding_id,
                    finding_fingerprint, first_candidate_hash, observation_number,
                    candidate_hash, reviewer_principal_id, created_at, created_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id().value(), request.scope().organizationId().value(),
                request.id().value(), value.finding().id().value(),
                value.finding().fingerprint().toString(),
                firstCandidateHash(request.scope().organizationId(), value.finding().id()),
                value.observationNumber(),
                value.candidateHash().value(), value.reviewerPrincipalId().value(),
                time(value.audit().createdAt()), creator(value.audit()));
        projections.rebuild(request.scope().organizationId(), request.id());
    }

    private void insertContextChildren(ContextPackage value) {
        int ordinal = 0;
        for (ReviewDiffHunk hunk : value.hunks()) {
            int patchBytes = hunk.patchBytes();
            if (patchBytes < 1) {
                throw new IllegalArgumentException("A persisted Context Hunk must contain Patch text");
            }
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_context_hunk (
                        context_package_id, ordinal, path, start_line, end_line,
                        patch_bytes, patch_hash
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.id().value(), ++ordinal, hunk.path().value(), hunk.startLine(),
                    hunk.endLine(), patchBytes, hunk.patchHash().value());
        }
        ordinal = 0;
        for (var command : value.testEvidence().commands()) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_context_command_evidence (
                        context_package_id, ordinal, command_evidence_id, evidence_sequence,
                        evidence_hash, command_kind, termination, exit_code, summary
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    value.id().value(), ++ordinal, command.evidence().id().value(),
                    command.evidence().sequence().value(), command.evidence().evidenceHash().value(),
                    command.commandKind().name(), command.termination().name(),
                    command.exitCode().orElse(null), command.summary().value());
        }
        ordinal = 0;
        for (AcceptanceResult acceptance : value.testEvidence().acceptanceResults()) {
            List<Map<String, Object>> coordinates = acceptance.evidence().stream().map(item -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", item.id().toString());
                result.put("sequence", item.sequence().value());
                result.put("evidenceHash", item.evidenceHash().value());
                return result;
            }).toList();
            jdbc.update(
                    """
                    INSERT INTO crewscope.review_context_acceptance_result (
                        context_package_id, ordinal, test_evidence_id, criterion_index,
                        status, summary, evidence_coordinates
                    ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                    """,
                    value.id().value(), ++ordinal, value.testEvidence().id().value(),
                    acceptance.criterionIndex(), acceptance.status().name(),
                    acceptance.summary().value(), json(coordinates));
        }
    }

    private void validateContextScalars(ResultSet row, ContextPackage value) throws SQLException {
        var reviewer = value.reviewer();
        boolean valid = value.id().value().equals(uuid(row, "id"))
                && value.scope().equals(scope(row))
                && value.taskId().value().equals(uuid(row, "task_id"))
                && value.taskExecutionId().value().equals(uuid(row, "task_execution_id"))
                && value.version() == row.getLong("package_version")
                && value.attempt() == row.getInt("attempt")
                && value.parentPackageId().map(ContextPackageId::value)
                        .equals(optionalUuid(row, "parent_package_id"))
                && value.subject().id().value().equals(uuid(row, "subject_id"))
                && value.subject().type().name().equals(row.getString("subject_type"))
                && value.subject().subjectHash().value().equals(row.getString("subject_hash"))
                && value.diff().artifact().id().value().equals(uuid(row, "diff_artifact_id"))
                && value.diff().artifact().finalHash().value().equals(row.getString("diff_final_hash"))
                && value.diff().codingTarget().snapshotId().value()
                        .equals(uuid(row, "coding_target_snapshot_id"))
                && value.diff().codingTarget().revision() == row.getLong("coding_target_revision")
                && value.diff().codingTarget().snapshotHash().value()
                        .equals(row.getString("coding_target_hash"))
                && value.diff().generation().value() == row.getLong("diff_generation")
                && value.diff().manifestHash().value().equals(row.getString("diff_manifest_hash"))
                && value.testEvidence().id().value().equals(uuid(row, "test_evidence_id"))
                && value.testEvidence().evidenceHash().value()
                        .equals(row.getString("test_evidence_hash"))
                && reviewer.agentProfileId().value().equals(uuid(row, "reviewer_agent_profile_id"))
                && reviewer.agentProfileVersion() == row.getLong("reviewer_agent_profile_version")
                && reviewer.agentPrincipalId().value()
                        .equals(uuid(row, "reviewer_agent_principal_id"))
                && reviewer.reviewerOwnerMemberId().map(TeamMemberId::value)
                        .equals(optionalUuid(row, "reviewer_owner_member_id"))
                && reviewer.subjectOwnerMemberId().map(TeamMemberId::value)
                        .equals(optionalUuid(row, "subject_owner_member_id"))
                && reviewer.relationship().name().equals(row.getString("reviewer_relationship"))
                && reviewer.templateVersion().key().value()
                        .equals(row.getString("reviewer_template_key"))
                && reviewer.templateVersion().version() == row.getLong("reviewer_template_version")
                && reviewer.templateHash().value().equals(row.getString("reviewer_template_hash"))
                && reviewer.configurationRevision().value()
                        == row.getLong("reviewer_configuration_revision")
                && reviewer.configurationHash().value()
                        .equals(row.getString("reviewer_configuration_hash"))
                && reviewer.policySnapshotId().value().equals(uuid(row, "policy_snapshot_id"))
                && reviewer.policySnapshotRevision() == row.getLong("policy_snapshot_revision")
                && reviewer.policySnapshotHash().value().equals(row.getString("policy_snapshot_hash"))
                && value.contextHash().value().equals(row.getString("context_hash"));
        if (!valid) {
            throw new IllegalStateException("ContextPackage scalar authority drifted from JSONB");
        }
    }

    private void validateContextChildren(ContextPackage value) {
        List<String> expectedHunks = value.hunks().stream()
                .map(hunk -> hunk.path().value() + ':' + hunk.startLine() + ':' + hunk.endLine()
                        + ':' + hunk.patchBytes() + ':' + hunk.patchHash().value())
                .toList();
        List<String> storedHunks = jdbc.query(
                """
                SELECT path, start_line, end_line, patch_bytes, patch_hash
                FROM crewscope.review_context_hunk
                WHERE context_package_id = ? ORDER BY ordinal
                """,
                (row, ignored) -> row.getString("path") + ':' + row.getInt("start_line") + ':'
                        + row.getInt("end_line") + ':' + row.getInt("patch_bytes") + ':'
                        + row.getString("patch_hash"),
                value.id().value());
        if (!expectedHunks.equals(storedHunks)) {
            throw new IllegalStateException("ContextPackage Hunk projection drifted");
        }
        List<String> expectedCommands = value.testEvidence().commands().stream()
                .map(command -> command.evidence().id() + ":" + command.evidence().sequence().value()
                        + ":" + command.evidence().evidenceHash().value() + ":"
                        + command.commandKind().name() + ":" + command.termination().name() + ":"
                        + command.exitCode().map(String::valueOf).orElse("null") + ":"
                        + command.summary().value())
                .toList();
        List<String> storedCommands = jdbc.query(
                """
                SELECT command_evidence_id, evidence_sequence, evidence_hash,
                    command_kind, termination, exit_code, summary
                FROM crewscope.review_context_command_evidence
                WHERE context_package_id = ? ORDER BY ordinal
                """,
                (row, ignored) -> row.getObject("command_evidence_id", UUID.class) + ":"
                        + row.getLong("evidence_sequence") + ":" + row.getString("evidence_hash")
                        + ":" + row.getString("command_kind") + ":" + row.getString("termination")
                        + ":" + Objects.toString(row.getObject("exit_code"), "null") + ":"
                        + row.getString("summary"),
                value.id().value());
        List<String> expectedAcceptance = value.testEvidence().acceptanceResults().stream()
                .map(result -> value.testEvidence().id() + ":" + result.criterionIndex() + ":"
                        + result.status().name() + ":" + result.summary().value() + ":"
                        + result.evidence().stream()
                                .map(item -> item.id() + ":" + item.sequence().value() + ":"
                                        + item.evidenceHash().value())
                                .toList())
                .toList();
        List<String> storedAcceptance = jdbc.query(
                """
                SELECT test_evidence_id, criterion_index, status, summary, evidence_coordinates
                FROM crewscope.review_context_acceptance_result
                WHERE context_package_id = ? ORDER BY ordinal
                """,
                (row, ignored) -> row.getObject("test_evidence_id", UUID.class) + ":"
                        + row.getInt("criterion_index") + ":" + row.getString("status") + ":"
                        + row.getString("summary") + ":"
                        + readEvidenceCoordinates(row.getString("evidence_coordinates")),
                value.id().value());
        if (!expectedCommands.equals(storedCommands)
                || !expectedAcceptance.equals(storedAcceptance)) {
            throw new IllegalStateException("ContextPackage evidence projection drifted");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readEvidenceCoordinates(String value) {
        Object decoded = objectMapper.readValue(value, List.class);
        if (!(decoded instanceof List<?> list)) {
            throw new IllegalStateException("Expected Review evidence coordinates array");
        }
        return list.stream().map(item -> {
            if (!(item instanceof Map<?, ?> coordinate)) {
                throw new IllegalStateException("Review evidence coordinate must be an object");
            }
            return coordinate.get("id") + ":" + coordinate.get("sequence") + ":"
                    + coordinate.get("evidenceHash");
        }).toList();
    }

    private ReviewRequestReference requestReference(ResultSet row, String prefix) throws SQLException {
        WorkItemScope scope = scope(row);
        ReviewSubjectReference subject = new ReviewSubjectReference(
                new ReviewSubjectId(uuid(row, prefix + "subject_id")),
                ReviewSubjectType.valueOf(row.getString(prefix + "subject_type")),
                new TaskFactHash(row.getString(prefix + "subject_hash")));
        ContextPackageReference context = new ContextPackageReference(
                new ContextPackageId(uuid(row, prefix + "context_package_id")),
                row.getLong(prefix + "context_package_version"),
                new TaskFactHash(row.getString(prefix + "context_hash")));
        return new ReviewRequestReference(
                scope,
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                new ReviewRequestId(uuid(row, "review_request_id")),
                row.getLong("review_request_revision"),
                row.getLong("review_request_version"),
                subject,
                context,
                new TaskFactHash(row.getString("review_request_hash")));
    }

    private ReviewerEligibilityDecision eligibility(ResultSet row) throws SQLException {
        String mode = row.getString("eligibility_mode");
        if ("INDEPENDENT_MEMBER".equals(mode)) {
            return ReviewerEligibilityDecision.strict();
        }
        Set<ResponsibilityRole> conflicts = readStringList(
                        row.getString("eligibility_conflicting_roles"))
                .stream()
                .map(ResponsibilityRole::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return ReviewerEligibilityDecision.singleMemberOverride(
                conflicts,
                new PolicyPackReference(
                        new PolicyPackId(uuid(row, "eligibility_policy_pack_id")),
                        row.getLong("eligibility_policy_pack_version")),
                row.getString("eligibility_override_reason"));
    }

    private String firstCandidateHash(
            OrganizationId organizationId, ReviewFindingId findingId) {
        return jdbc.queryForObject(
                "SELECT candidate_hash FROM crewscope.review_finding WHERE organization_id = ? AND id = ?",
                String.class,
                organizationId.value(),
                findingId.value());
    }

    private static String findingSelect(String predicate) {
        return """
                SELECT f.*,
                    r.subject_id AS request_subject_id,
                    r.subject_type AS request_subject_type,
                    r.subject_hash AS request_subject_hash,
                    r.context_package_id AS request_context_package_id,
                    r.context_package_version AS request_context_package_version,
                    r.context_hash AS request_context_hash
                FROM crewscope.review_finding f
                JOIN crewscope.review_request r ON r.id = f.review_request_id
                WHERE
                """ + predicate;
    }

    private static String decisionSelect(String predicate) {
        return """
                SELECT d.*,
                    r.subject_id AS request_subject_id,
                    r.subject_type AS request_subject_type,
                    r.subject_hash AS request_subject_hash,
                    r.context_package_id AS request_context_package_id,
                    r.context_package_version AS request_context_package_version,
                    r.context_hash AS request_context_hash
                FROM crewscope.review_decision d
                JOIN crewscope.review_request r ON r.id = d.review_request_id
                WHERE
                """ + predicate;
    }

    private static String observationSelect(String predicate) {
        return """
                SELECT o.*, r.team_id, r.workspace_id, r.project_id,
                    r.task_id, r.task_execution_id, r.attempt,
                    r.id AS review_request_id,
                    r.revision AS review_request_revision,
                    r.version AS review_request_version,
                    r.request_hash AS review_request_hash,
                    r.subject_id AS request_subject_id,
                    r.subject_type AS request_subject_type,
                    r.subject_hash AS request_subject_hash,
                    r.context_package_id AS request_context_package_id,
                    r.context_package_version AS request_context_package_version,
                    r.context_hash AS request_context_hash
                FROM crewscope.review_finding_observation o
                JOIN crewscope.review_request r ON r.id = o.review_request_id
                WHERE
                """ + predicate;
    }

    private static String roundSelect(String predicate) {
        return """
                SELECT m.*,
                    r.subject_id AS request_subject_id,
                    r.subject_type AS request_subject_type,
                    r.subject_hash AS request_subject_hash,
                    r.context_package_id AS request_context_package_id,
                    r.context_package_version AS request_context_package_version,
                    r.context_hash AS request_context_hash,
                    r.attempt AS attempt,
                    m.source_request_id AS review_request_id,
                    m.source_request_revision AS review_request_revision,
                    m.source_request_version AS review_request_version,
                    m.source_request_hash AS review_request_hash
                FROM crewscope.review_modification_round m
                JOIN crewscope.review_request r ON r.id = m.source_request_id
                WHERE
                """ + predicate;
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(String value) {
        Object decoded = objectMapper.readValue(value, List.class);
        if (!(decoded instanceof List<?> list)) {
            throw new IllegalStateException("Expected a JSON array");
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static WorkItemScope scope(ResultSet row) throws SQLException {
        return new WorkItemScope(
                new OrganizationId(uuid(row, "organization_id")),
                new TeamId(uuid(row, "team_id")),
                new WorkspaceId(uuid(row, "workspace_id")),
                new WorkProjectId(uuid(row, "project_id")));
    }

    private static AuditMetadata immutableAudit(ResultSet row) throws SQLException {
        PrincipalId creator = new PrincipalId(uuid(row, "created_by_principal_id"));
        UtcTimestamp createdAt = timestamp(row, "created_at");
        return AuditMetadata.createdBy(creator, createdAt);
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                timestamp(row, "updated_at"));
    }

    private static UUID creator(AuditMetadata audit) {
        return audit.createdBy().orElseThrow().value();
    }

    private static UUID updater(AuditMetadata audit) {
        return audit.updatedBy().orElseThrow().value();
    }

    private static OffsetDateTime time(UtcTimestamp value) {
        return value.toOffsetDateTime();
    }

    private static UtcTimestamp timestamp(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class).toInstant());
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(uuid(row, column));
    }

    private static Optional<String> optionalString(ResultSet row, String column)
            throws SQLException {
        return Optional.ofNullable(row.getString(column));
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Tenant-scoped Review query returned multiple rows");
        }
        return values.stream().findFirst();
    }
}
