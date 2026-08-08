package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable message payload plus separate moderation metadata. */
@Entity
@Table(name = "message", schema = "crewscope")
class MessageEntity {

    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(nullable = false) long sequence;
    @Column(name = "message_type", nullable = false, length = 32) String messageType;
    @Column(name = "participant_id") UUID participantId;
    @Column(name = "author_principal_id") UUID authorPrincipalId;
    @Column(name = "content_markdown", nullable = false) String contentMarkdown;
    @Column(name = "client_message_key", length = 200) String clientMessageKey;
    @Column(name = "moderation_status", nullable = false, length = 32) String moderationStatus;
    @Column(name = "moderated_at") Instant moderatedAt;
    @Column(name = "moderated_by_principal_id") UUID moderatedByPrincipalId;
    @Column(name = "moderation_reason_code", length = 100) String moderationReasonCode;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;

    protected MessageEntity() {}
}
