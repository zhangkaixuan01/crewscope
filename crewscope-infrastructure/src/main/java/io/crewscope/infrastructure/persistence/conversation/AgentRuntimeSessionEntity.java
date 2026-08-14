package io.crewscope.infrastructure.persistence.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

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
