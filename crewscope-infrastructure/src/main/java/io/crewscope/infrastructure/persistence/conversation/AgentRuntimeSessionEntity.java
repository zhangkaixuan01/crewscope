package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable AgentScope state binding for one Personal Agent Conversation. */
@Entity
@Table(name = "agent_runtime_session", schema = "crewscope")
class AgentRuntimeSessionEntity {

    @Id UUID id;
    @Column(name = "organization_id", nullable = false) UUID organizationId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "workspace_id", nullable = false) UUID workspaceId;
    @Column(name = "conversation_id", nullable = false) UUID conversationId;
    @Column(name = "owner_member_id", nullable = false) UUID ownerMemberId;
    @Column(name = "owner_principal_id", nullable = false) UUID ownerPrincipalId;
    @Column(name = "personal_agent_principal_id", nullable = false) UUID personalAgentPrincipalId;
    @Column(name = "agent_profile_id", nullable = false) UUID agentProfileId;
    @Column(name = "agent_profile_version", nullable = false) long agentProfileVersion;
    @Column(name = "agent_ownership_type", length = 32) String agentOwnershipType;
    @Column(name = "agent_runtime_role", length = 32) String agentRuntimeRole;
    @Column(name = "agent_template_key", length = 64) String agentTemplateKey;
    @Column(name = "agent_template_version") Long agentTemplateVersion;
    @Column(name = "agent_configuration_revision") Long agentConfigurationRevision;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "agent_configuration_hash", length = 64, columnDefinition = "char(64)")
    String agentConfigurationHash;
    // V10 keeps the Personal binding columns while also exposing one common Agent identity shape.
    @Column(name = "session_purpose", nullable = false, length = 32) String sessionPurpose;
    @Column(name = "agent_principal_id", nullable = false) UUID agentPrincipalId;
    @Column(name = "agent_principal_type", nullable = false, length = 32) String agentPrincipalType;
    @Column(name = "agent_profile_type", nullable = false, length = 32) String agentProfileType;
    @Column(name = "agent_scope_user_id", nullable = false, length = 500) String agentScopeUserId;
    @Column(name = "agent_scope_session_id", nullable = false, length = 500) String agentScopeSessionId;
    @Column(name = "state_reference", nullable = false, length = 500) String stateReference;
    @Column(nullable = false, length = 32) String status;
    @Version @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "created_by_principal_id", nullable = false) UUID createdByPrincipalId;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "updated_by_principal_id", nullable = false) UUID updatedByPrincipalId;

    protected AgentRuntimeSessionEntity() {}
}
