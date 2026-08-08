package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable Conversation-to-WorkItem relation. */
@Entity
@Table(name = "conversation_work_item_link", schema = "crewscope")
class ConversationWorkItemLinkEntity {

    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "work_project_id", nullable = false) UUID workProjectId;
    @Column(name = "work_item_id", nullable = false) UUID workItemId;
    @Column(nullable = false, length = 32) String origin;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected ConversationWorkItemLinkEntity() {}
}
