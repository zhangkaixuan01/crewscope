package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Scalar JPA snapshot for a tenant-scoped Conversation. */
@Entity
@Table(name = "conversation", schema = "crewscope")
class ConversationEntity {

    @Id
    UUID id;

    @Column(name = "organization_id", nullable = false)
    UUID organizationId;

    @Column(name = "team_id", nullable = false)
    UUID teamId;

    @Column(name = "workspace_id", nullable = false)
    UUID workspaceId;

    @Column(name = "owner_member_id", nullable = false)
    UUID ownerMemberId;

    @Column(name = "owner_principal_id", nullable = false)
    UUID ownerPrincipalId;

    @Column(name = "personal_agent_principal_id", nullable = false)
    UUID personalAgentPrincipalId;

    @Column(nullable = false, length = 200)
    String title;

    @Column(nullable = false, length = 16)
    String visibility;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "last_message_sequence")
    Long lastMessageSequence;

    @Version
    @Column(nullable = false)
    long version;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "created_by_principal_id", nullable = false)
    UUID createdByPrincipalId;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Column(name = "updated_by_principal_id", nullable = false)
    UUID updatedByPrincipalId;

    protected ConversationEntity() {}
}
