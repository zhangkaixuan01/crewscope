package io.crewscope.infrastructure.persistence.action;

import io.crewscope.application.action.ActionBundleRepository;
import io.crewscope.application.action.ConfirmationRepository;
import io.crewscope.domain.action.ActionAuthoritySnapshot;
import io.crewscope.domain.action.ActionBundle;
import io.crewscope.domain.action.ActionBundleDigest;
import io.crewscope.domain.action.ActionBundleId;
import io.crewscope.domain.action.ActionCancellationReason;
import io.crewscope.domain.action.ActionDependency;
import io.crewscope.domain.action.ActionDigest;
import io.crewscope.domain.action.ActionPolicyReference;
import io.crewscope.domain.action.ActionRiskLevel;
import io.crewscope.domain.action.ActionTargetPrecondition;
import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.action.ConfirmationId;
import io.crewscope.domain.action.ConfirmationStatus;
import io.crewscope.domain.action.ConfirmedActionReference;
import io.crewscope.domain.action.CreateDraftPullRequestActionParameters;
import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.action.PlannedAction;
import io.crewscope.domain.action.PlannedActionId;
import io.crewscope.domain.action.ProviderAuthorizationReference;
import io.crewscope.domain.action.PushBranchActionParameters;
import io.crewscope.domain.action.RepositoryBranchReference;
import io.crewscope.domain.action.ResponsibilityReference;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionReference;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewDiffReference;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestReference;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewSubjectReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.SafetyEnforcementOverlayId;
import io.crewscope.domain.task.SafetyEnforcementOverlayReference;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL store for immutable Action graphs and exact human Confirmations. */
@Repository
public class JdbcActionDefinitionRepositoryAdapter
        implements ActionBundleRepository, ConfirmationRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ActionAuthorityJsonCodec authorityCodec;

    public JdbcActionDefinitionRepositoryAdapter(
            JdbcTemplate jdbc, ActionAuthorityJsonCodec authorityCodec) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.authorityCodec = Objects.requireNonNull(authorityCodec, "authorityCodec");
    }

    @Override
    public Optional<ActionBundle> findById(
            OrganizationId organizationId, ActionBundleId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.action_bundle WHERE organization_id = ? AND id = ?",
                this::bundle,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<ActionBundle> findByReviewDecision(
            OrganizationId organizationId, ReviewDecisionId reviewDecisionId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.action_bundle
                WHERE organization_id = ? AND review_decision_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """,
                this::bundle,
                organizationId.value(), reviewDecisionId.value()));
    }

    @Override
    public List<ActionBundle> findByTaskExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId) {
        return jdbc.query(
                """
                SELECT * FROM crewscope.action_bundle
                WHERE organization_id = ? AND task_execution_id = ?
                ORDER BY created_at, id
                """,
                this::bundle,
                organizationId.value(), taskExecutionId.value());
    }

    @Override
    @Transactional
    public void insert(ActionBundle bundle) {
        ActionBundle value = Objects.requireNonNull(bundle, "bundle");
        ActionAuthoritySnapshot a = value.authority();
        var request = a.reviewDecision().reviewRequest();
        var provider = a.providerAuthorization();
        var target = a.targetPrecondition();
        MapSqlParameterSource p = scope(new MapSqlParameterSource(), a.scope())
                .addValue("id", value.id().value())
                .addValue("workItemId", a.workItemId().value())
                .addValue("taskId", a.taskId().value())
                .addValue("executionId", a.taskExecutionId().value())
                .addValue("attempt", a.attempt())
                .addValue("decisionId", a.reviewDecision().id().value())
                .addValue("decisionRevision", a.reviewDecision().revision())
                .addValue("decisionType", a.reviewDecision().type().name())
                .addValue("decisionHash", a.reviewDecision().decisionHash().value())
                .addValue("requestId", request.id().value())
                .addValue("requestRevision", request.revision())
                .addValue("requestVersion", request.version())
                .addValue("requestHash", request.requestHash().value())
                .addValue("subjectId", request.subject().id().value())
                .addValue("subjectHash", request.subject().subjectHash().value())
                .addValue("contextId", request.contextPackage().id().value())
                .addValue("contextHash", request.contextPackage().contextHash().value())
                .addValue("diffId", a.diff().artifact().id().value())
                .addValue("diffHash", a.diff().artifact().finalHash().value())
                .addValue("responsibilityId", a.responsibility().id().value())
                .addValue("responsibilityVersion", a.responsibility().version())
                .addValue("responsibilityRole", a.responsibility().role().name())
                .addValue("responsibilityPrincipal", a.responsibility().actorPrincipalId().value())
                .addValue("bindingId", provider.bindingId().value())
                .addValue("bindingVersion", provider.bindingVersion())
                .addValue("definitionId", provider.definitionId().value())
                .addValue("definitionVersion", provider.definitionVersion())
                .addValue("implementationId", provider.implementationId().value())
                .addValue("implementationVersion", provider.implementationVersion())
                .addValue("providerType", provider.providerType().name())
                .addValue("executionIdentity", provider.executionIdentity().name())
                .addValue("connectionId", provider.connectionId().value())
                .addValue("connectionVersion", provider.connectionVersion())
                .addValue("grantId", provider.grantId().value())
                .addValue("grantVersion", provider.grantVersion())
                .addValue("accessHash", provider.effectiveAccessHash().value())
                .addValue("policyId", a.policy().id().value())
                .addValue("policyRevision", a.policy().revision())
                .addValue("policyHash", a.policy().snapshotHash().value())
                .addValue("safetyId", a.safetyOverlay().id().value())
                .addValue("safetyVersion", a.safetyOverlay().version())
                .addValue("safetyHash", a.safetyOverlay().overlayHash().value())
                .addValue("repositoryId", target.repositoryBindingId().value())
                .addValue("repositoryVersion", target.repositoryBindingVersion())
                .addValue("repositoryKey", target.repositoryKey().value())
                .addValue("defaultBranch", target.defaultBranch().value())
                .addValue("codingTargetId", target.codingTarget().snapshotId().value())
                .addValue("codingTargetRevision", target.codingTarget().revision())
                .addValue("codingTargetHash", target.codingTarget().snapshotHash().value())
                .addValue("baseline", target.baselineCommit().value())
                .addValue("delivery", target.deliveryCommit().value())
                .addValue("authority", authorityCodec.encode(a))
                .addValue("validUntil", time(value.validUntil()))
                .addValue("digest", value.digest().toString())
                .addValue("version", value.version())
                .addValue("createdAt", time(value.audit().createdAt()))
                .addValue("createdBy", creator(value.audit()));
        named.update(
                """
                INSERT INTO crewscope.action_bundle (
                    id, organization_id, team_id, workspace_id, project_id,
                    work_item_id, task_id, task_execution_id, attempt,
                    review_decision_id, review_decision_revision, review_decision_type,
                    review_decision_hash, review_request_id, review_request_revision,
                    review_request_version, review_request_hash, review_subject_id,
                    review_subject_hash, review_context_package_id, review_context_hash,
                    review_diff_artifact_id, review_diff_final_hash,
                    responsibility_assignment_id, responsibility_version,
                    responsibility_role, responsibility_principal_id,
                    provider_binding_id, provider_binding_version, provider_definition_id,
                    provider_definition_version, provider_implementation_id,
                    provider_implementation_version, provider_type, provider_execution_identity,
                    connection_id, connection_version, connection_grant_id,
                    connection_grant_version, effective_access_hash,
                    policy_snapshot_id, policy_snapshot_revision, policy_snapshot_hash,
                    safety_overlay_id, safety_overlay_version, safety_overlay_hash,
                    repository_binding_id, repository_binding_version, repository_key,
                    default_branch, coding_target_snapshot_id, coding_target_revision,
                    coding_target_hash, baseline_commit, delivery_commit, authority_snapshot,
                    valid_until, bundle_digest, version, created_at, created_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :workItemId, :taskId, :executionId, :attempt,
                    :decisionId, :decisionRevision, :decisionType, :decisionHash,
                    :requestId, :requestRevision, :requestVersion, :requestHash,
                    :subjectId, :subjectHash, :contextId, :contextHash, :diffId, :diffHash,
                    :responsibilityId, :responsibilityVersion, :responsibilityRole,
                    :responsibilityPrincipal, :bindingId, :bindingVersion, :definitionId,
                    :definitionVersion, :implementationId, :implementationVersion,
                    :providerType, :executionIdentity, :connectionId, :connectionVersion,
                    :grantId, :grantVersion, :accessHash, :policyId, :policyRevision,
                    :policyHash, :safetyId, :safetyVersion, :safetyHash, :repositoryId,
                    :repositoryVersion, :repositoryKey, :defaultBranch, :codingTargetId,
                    :codingTargetRevision, :codingTargetHash, :baseline, :delivery,
                    CAST(:authority AS JSONB), :validUntil, :digest, :version,
                    :createdAt, :createdBy
                )
                """,
                p);
        for (PlannedAction action : value.actions()) {
            insertAction(value, action);
        }
    }

    private void insertAction(ActionBundle bundle, PlannedAction action) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", action.id().value())
                .addValue("bundleId", bundle.id().value())
                .addValue("sequence", action.sequence())
                .addValue("kind", action.kind().name())
                .addValue("risk", action.risk().name())
                .addValue("validUntil", time(action.validUntil()))
                .addValue("digest", action.digest().toString())
                .addValue("snapshot", authorityCodec.parameters(action.kind().name()));
        if (action.parameters() instanceof PushBranchActionParameters push) {
            p.addValue("externalRepositoryId", push.repositoryId().value())
                    .addValue("connectionId", push.connectionId().value())
                    .addValue("branch", push.branch().value())
                    .addValue("delivery", push.deliveryHead().value())
                    .addValue("expectedRemote", push.expectedRemoteHead()
                            .map(RepositoryCommitId::value).orElse(null))
                    .addValue("prHead", null).addValue("prBase", null)
                    .addValue("prSha", null).addValue("prTitle", null)
                    .addValue("prBody", null).addValue("prDraft", null);
        } else if (action.parameters() instanceof CreateDraftPullRequestActionParameters pr) {
            p.addValue("externalRepositoryId", pr.repositoryId().value())
                    .addValue("connectionId", pr.connectionId().value())
                    .addValue("branch", null).addValue("delivery", null)
                    .addValue("expectedRemote", null)
                    .addValue("prHead", pr.head().value())
                    .addValue("prBase", pr.base().value())
                    .addValue("prSha", pr.headSha().value())
                    .addValue("prTitle", pr.title()).addValue("prBody", pr.body())
                    .addValue("prDraft", pr.draft());
        } else {
            throw new IllegalStateException("Unsupported PlannedAction parameters");
        }
        named.update(
                """
                INSERT INTO crewscope.planned_action (
                    id, action_bundle_id, sequence, action_kind, external_repository_id,
                    connection_id, branch_full_ref, delivery_head, expected_remote_head,
                    pr_head, pr_base, pr_head_sha, pr_title, pr_body, pr_draft,
                    parameter_snapshot, risk, valid_until, action_digest
                ) VALUES (
                    :id, :bundleId, :sequence, :kind, :externalRepositoryId,
                    :connectionId, :branch, :delivery, :expectedRemote,
                    :prHead, :prBase, :prSha, :prTitle, :prBody, :prDraft,
                    CAST(:snapshot AS JSONB), :risk, :validUntil, :digest
                )
                """,
                p);
        action.dependencies().forEach(dependency -> jdbc.update(
                """
                INSERT INTO crewscope.planned_action_dependency (
                    action_bundle_id, action_id, predecessor_action_id
                ) VALUES (?, ?, ?)
                """,
                bundle.id().value(), action.id().value(),
                dependency.predecessorActionId().value()));
    }

    @Override
    @Transactional
    public Confirmation insert(Confirmation confirmation) {
        Confirmation value = Objects.requireNonNull(confirmation, "confirmation");
        insertConfirmation(value);
        return findById(value.scope().organizationId(), value.id()).orElseThrow();
    }

    private void insertConfirmation(Confirmation value) {
        MapSqlParameterSource p = scope(new MapSqlParameterSource(), value.scope())
                .addValue("id", value.id().value())
                .addValue("bundleId", value.bundleId().value())
                .addValue("digest", value.bundleDigest().toString())
                .addValue("confirmedBy", value.confirmedByPrincipalId().value())
                .addValue("confirmedAt", time(value.confirmedAt()))
                .addValue("validUntil", time(value.validUntil()))
                .addValue("status", value.status().name())
                .addValue("cancellation", value.cancellationReason().map(Enum::name).orElse(null))
                .addValue("version", value.version())
                .addValue("createdAt", time(value.audit().createdAt()))
                .addValue("createdBy", creator(value.audit()))
                .addValue("updatedAt", time(value.audit().updatedAt()))
                .addValue("updatedBy", updater(value.audit()));
        named.update(
                """
                INSERT INTO crewscope.action_confirmation (
                    id, organization_id, team_id, workspace_id, project_id,
                    action_bundle_id, bundle_digest, confirmed_by_principal_id,
                    confirmed_at, valid_until, status, cancellation_reason, version,
                    created_at, created_by_principal_id, updated_at, updated_by_principal_id
                ) VALUES (
                    :id, :organizationId, :teamId, :workspaceId, :projectId,
                    :bundleId, :digest, :confirmedBy, :confirmedAt, :validUntil,
                    :status, :cancellation, :version, :createdAt, :createdBy,
                    :updatedAt, :updatedBy
                )
                """,
                p);
        value.actions().forEach(action -> jdbc.update(
                """
                INSERT INTO crewscope.confirmation_action (
                    confirmation_id, action_bundle_id, action_id, sequence, action_digest
                ) VALUES (?, ?, ?, ?, ?)
                """,
                value.id().value(), value.bundleId().value(), action.actionId().value(),
                action.sequence(), action.actionDigest().toString()));
    }

    @Override
    @Transactional
    public Confirmation update(Confirmation confirmation) {
        Confirmation value = Objects.requireNonNull(confirmation, "confirmation");
        int changed = jdbc.update(
                """
                UPDATE crewscope.action_confirmation
                SET status = ?, cancellation_reason = ?, version = ?,
                    updated_at = ?, updated_by_principal_id = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """,
                value.status().name(), value.cancellationReason().map(Enum::name).orElse(null),
                value.version(), time(value.audit().updatedAt()), updater(value.audit()),
                value.scope().organizationId().value(), value.id().value(), value.version() - 1);
        if (changed != 1) {
            throw new OptimisticLockConflictException(
                    "Confirmation", value.id(), value.version() - 1, value.version());
        }
        return findById(value.scope().organizationId(), value.id()).orElseThrow();
    }

    @Override
    public Optional<Confirmation> findById(
            OrganizationId organizationId, ConfirmationId id) {
        return one(jdbc.query(
                "SELECT * FROM crewscope.action_confirmation WHERE organization_id = ? AND id = ?",
                this::confirmation,
                organizationId.value(), id.value()));
    }

    @Override
    public Optional<Confirmation> findActiveByBundle(
            OrganizationId organizationId, ActionBundleId bundleId) {
        return one(jdbc.query(
                """
                SELECT * FROM crewscope.action_confirmation
                WHERE organization_id = ? AND action_bundle_id = ? AND status = 'ACTIVE'
                """,
                this::confirmation,
                organizationId.value(), bundleId.value()));
    }

    private ActionBundle bundle(ResultSet row, int ignored) throws SQLException {
        WorkItemScope scope = scope(row);
        ActionAuthorityJsonCodec.Supplement supplement = authorityCodec.decode(
                row.getString("authority_snapshot"));
        var subject = new ReviewSubjectReference(
                new ReviewSubjectId(uuid(row, "review_subject_id")),
                supplement.subjectType(),
                hash(row, "review_subject_hash"));
        var context = supplement.context(
                new ContextPackageId(uuid(row, "review_context_package_id")),
                hash(row, "review_context_hash"));
        var request = new ReviewRequestReference(
                scope,
                new TaskId(uuid(row, "task_id")),
                new TaskExecutionId(uuid(row, "task_execution_id")),
                row.getInt("attempt"),
                new ReviewRequestId(uuid(row, "review_request_id")),
                row.getLong("review_request_revision"),
                row.getLong("review_request_version"),
                subject,
                context,
                hash(row, "review_request_hash"));
        var decision = new ReviewDecisionReference(
                new ReviewDecisionId(uuid(row, "review_decision_id")),
                row.getLong("review_decision_revision"),
                request,
                ReviewDecisionType.valueOf(row.getString("review_decision_type")),
                hash(row, "review_decision_hash"));
        var codingTarget = new CodingTargetSnapshotReference(
                new CodingTargetSnapshotId(uuid(row, "coding_target_snapshot_id")),
                row.getLong("coding_target_revision"),
                hash(row, "coding_target_hash"));
        var diff = new ReviewDiffReference(
                scope,
                request.taskId(),
                request.taskExecutionId(),
                request.attempt(),
                new DiffArtifactReference(
                        new DiffArtifactId(uuid(row, "review_diff_artifact_id")),
                        hash(row, "review_diff_final_hash")),
                codingTarget,
                new RepositoryCommitId(row.getString("baseline_commit")),
                new RepositoryCommitId(row.getString("delivery_commit")),
                supplement.diffGeneration(),
                supplement.diffManifestHash(),
                supplement.patchArtifact(),
                supplement.changedPaths());
        var provider = new ProviderAuthorizationReference(
                new ProviderBindingId(uuid(row, "provider_binding_id")),
                row.getLong("provider_binding_version"),
                new ProviderDefinitionId(uuid(row, "provider_definition_id")),
                row.getLong("provider_definition_version"),
                new ProviderImplementationId(uuid(row, "provider_implementation_id")),
                row.getLong("provider_implementation_version"),
                ProviderType.valueOf(row.getString("provider_type")),
                ProviderExecutionIdentity.valueOf(row.getString("provider_execution_identity")),
                new ConnectionId(uuid(row, "connection_id")),
                row.getLong("connection_version"),
                new ConnectionGrantId(uuid(row, "connection_grant_id")),
                row.getLong("connection_grant_version"),
                hash(row, "effective_access_hash"));
        var target = new ActionTargetPrecondition(
                new RepositoryBindingId(uuid(row, "repository_binding_id")),
                row.getLong("repository_binding_version"),
                new RepositoryKey(row.getString("repository_key")),
                new RepositoryBranchName(row.getString("default_branch")),
                codingTarget,
                diff.baselineCommit(),
                diff.deliveryCommit());
        ActionAuthoritySnapshot authority = new ActionAuthoritySnapshot(
                scope,
                new WorkItemId(uuid(row, "work_item_id")),
                request.taskId(),
                request.taskExecutionId(),
                request.attempt(),
                decision,
                diff,
                new ResponsibilityReference(
                        new ResponsibilityAssignmentId(uuid(row, "responsibility_assignment_id")),
                        row.getLong("responsibility_version"),
                        ResponsibilityRole.valueOf(row.getString("responsibility_role")),
                        new PrincipalId(uuid(row, "responsibility_principal_id"))),
                provider,
                new ActionPolicyReference(
                        new PolicySnapshotId(uuid(row, "policy_snapshot_id")),
                        row.getLong("policy_snapshot_revision"),
                        hash(row, "policy_snapshot_hash")),
                new SafetyEnforcementOverlayReference(
                        new SafetyEnforcementOverlayId(uuid(row, "safety_overlay_id")),
                        row.getLong("safety_overlay_version"),
                        hash(row, "safety_overlay_hash")),
                target);
        ActionBundleId bundleId = new ActionBundleId(uuid(row, "id"));
        return ActionBundle.reconstitute(
                bundleId,
                authority,
                actions(bundleId, authority),
                timestamp(row, "valid_until"),
                new ActionBundleDigest(hash(row, "bundle_digest")),
                row.getLong("version"),
                AuditMetadata.createdBy(
                        new PrincipalId(uuid(row, "created_by_principal_id")),
                        timestamp(row, "created_at")));
    }

    private List<PlannedAction> actions(
            ActionBundleId bundleId, ActionAuthoritySnapshot authority) {
        return jdbc.query(
                """
                SELECT * FROM crewscope.planned_action
                WHERE action_bundle_id = ? ORDER BY sequence
                """,
                (row, ignored) -> {
                    PlannedActionId id = new PlannedActionId(uuid(row, "id"));
                    List<ActionDependency> dependencies = jdbc.query(
                            """
                            SELECT predecessor_action_id
                            FROM crewscope.planned_action_dependency
                            WHERE action_bundle_id = ? AND action_id = ?
                            ORDER BY predecessor_action_id
                            """,
                            (dependency, index) -> new ActionDependency(
                                    new PlannedActionId(uuid(dependency, "predecessor_action_id"))),
                            bundleId.value(), id.value());
                    String kind = row.getString("action_kind");
                    var parameters = "PUSH_BRANCH".equals(kind)
                            ? new PushBranchActionParameters(
                                    new ExternalRepositoryId(row.getString("external_repository_id")),
                                    new RepositoryBranchReference(row.getString("branch_full_ref")),
                                    new RepositoryCommitId(row.getString("delivery_head")),
                                    Optional.ofNullable(row.getString("expected_remote_head"))
                                            .map(RepositoryCommitId::new),
                                    new ConnectionId(uuid(row, "connection_id")))
                            : new CreateDraftPullRequestActionParameters(
                                    new ExternalRepositoryId(row.getString("external_repository_id")),
                                    new RepositoryBranchName(row.getString("pr_head")),
                                    new RepositoryBranchName(row.getString("pr_base")),
                                    new RepositoryCommitId(row.getString("pr_head_sha")),
                                    row.getString("pr_title"),
                                    row.getString("pr_body"),
                                    row.getBoolean("pr_draft"),
                                    new ConnectionId(uuid(row, "connection_id")));
                    return PlannedAction.reconstitute(
                            id,
                            row.getInt("sequence"),
                            parameters,
                            dependencies,
                            authority,
                            ActionRiskLevel.valueOf(row.getString("risk")),
                            timestamp(row, "valid_until"),
                            new ActionDigest(hash(row, "action_digest")));
                },
                bundleId.value());
    }

    private Confirmation confirmation(ResultSet row, int ignored) throws SQLException {
        ConfirmationId id = new ConfirmationId(uuid(row, "id"));
        List<ConfirmedActionReference> actions = jdbc.query(
                """
                SELECT action_id, sequence, action_digest
                FROM crewscope.confirmation_action
                WHERE confirmation_id = ? ORDER BY sequence
                """,
                (item, index) -> new ConfirmedActionReference(
                        new PlannedActionId(uuid(item, "action_id")),
                        item.getInt("sequence"),
                        new ActionDigest(hash(item, "action_digest"))),
                id.value());
        return Confirmation.reconstitute(
                id,
                scope(row),
                new ActionBundleId(uuid(row, "action_bundle_id")),
                new ActionBundleDigest(hash(row, "bundle_digest")),
                actions,
                new PrincipalId(uuid(row, "confirmed_by_principal_id")),
                timestamp(row, "confirmed_at"),
                timestamp(row, "valid_until"),
                ConfirmationStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("cancellation_reason"))
                        .map(ActionCancellationReason::valueOf),
                row.getLong("version"),
                audit(row));
    }

    private static MapSqlParameterSource scope(
            MapSqlParameterSource values, WorkItemScope scope) {
        return values.addValue("organizationId", scope.organizationId().value())
                .addValue("teamId", scope.teamId().value())
                .addValue("workspaceId", scope.workspaceId().value())
                .addValue("projectId", scope.projectId().value());
    }

    private static WorkItemScope scope(ResultSet row) throws SQLException {
        return new WorkItemScope(
                new OrganizationId(uuid(row, "organization_id")),
                new TeamId(uuid(row, "team_id")),
                new WorkspaceId(uuid(row, "workspace_id")),
                new WorkProjectId(uuid(row, "project_id")));
    }

    private static AuditMetadata audit(ResultSet row) throws SQLException {
        return new AuditMetadata(
                Optional.of(new PrincipalId(uuid(row, "created_by_principal_id"))),
                timestamp(row, "created_at"),
                Optional.of(new PrincipalId(uuid(row, "updated_by_principal_id"))),
                timestamp(row, "updated_at"));
    }

    private static TaskFactHash hash(ResultSet row, String column) throws SQLException {
        return new TaskFactHash(row.getString(column));
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
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException("Expected at most one Action row");
        }
        return values.stream().findFirst();
    }
}
