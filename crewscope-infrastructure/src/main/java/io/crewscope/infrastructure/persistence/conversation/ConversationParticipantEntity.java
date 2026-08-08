package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for a Conversation participant lifecycle. */
@Entity
@Table(name = "conversation_participant", schema = "crewscope")
class ConversationParticipantEntity {

    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "principal_id", nullable = false) UUID principalId;
    @Column(name = "team_member_id") UUID teamMemberId;
    @Column(nullable = false, length = 32) String role;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "joined_by_principal_id", nullable = false) UUID joinedByPrincipalId;
    @Column(name = "joined_at", nullable = false) Instant joinedAt;
    @Column(name = "left_at") Instant leftAt;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;

    protected ConversationParticipantEntity() {}
}
