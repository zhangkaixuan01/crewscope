package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Expanded TaskIntent snapshot used for relational integrity and atomic confirmation. */
@Entity
@Table(name = "task_intent", schema = "crewscope")
class TaskIntentEntity {

    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "proposed_by_principal_id", nullable = false) UUID proposedByPrincipalId;
    @Column(name = "proposal_revision", nullable = false) int proposalRevision;
    @Column(name = "work_project_id", nullable = false) UUID workProjectId;
    @Column(nullable = false) String objective;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptance_criteria", nullable = false, columnDefinition = "jsonb")
    List<String> acceptanceCriteria;
    @Column(name = "owner_principal_id", nullable = false) UUID ownerPrincipalId;
    @Column(name = "owner_principal_type", nullable = false, length = 32) String ownerPrincipalType;
    @Column(name = "owner_member_id", nullable = false) UUID ownerMemberId;
    @Column(name = "executor_principal_id") UUID executorPrincipalId;
    @Column(name = "executor_principal_type", length = 32) String executorPrincipalType;
    @Column(name = "executor_member_id") UUID executorMemberId;
    @Column(name = "gate_reviewer_principal_id") UUID gateReviewerPrincipalId;
    @Column(name = "gate_reviewer_principal_type", length = 32) String gateReviewerPrincipalType;
    @Column(name = "gate_reviewer_member_id") UUID gateReviewerMemberId;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "decided_by_principal_id") UUID decidedByPrincipalId;
    @Column(name = "decided_at") Instant decidedAt;
    @Column(name = "decision_reason") String decisionReason;
    @Column(name = "confirmed_work_item_id") UUID confirmedWorkItemId;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;

    protected TaskIntentEntity() {}
}
