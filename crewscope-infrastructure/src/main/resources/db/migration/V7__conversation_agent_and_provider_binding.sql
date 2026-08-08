-- M2 conversations, Personal Agent runtime sessions and Provider bindings keep complete tenant
-- scope on every relationship. Additional candidate keys on V2/V6 tables let composite foreign
-- keys prove credential and AgentProfile ownership without relying on application joins.
ALTER TABLE crewscope.credential_secret
    ADD CONSTRAINT uk_credential_secret_organization_id
        UNIQUE (organization_id, id);

ALTER TABLE crewscope.agent_profile
    ADD CONSTRAINT uk_agent_profile_runtime_binding
        UNIQUE (
            organization_id, team_id, workspace_id, id,
            agent_principal_id, owner_member_id
        );

CREATE TABLE crewscope.conversation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    owner_member_id UUID NOT NULL,
    owner_principal_id UUID NOT NULL,
    personal_agent_principal_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_message_sequence BIGINT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_conversation_scope_id
        UNIQUE (organization_id, team_id, workspace_id, id),
    CONSTRAINT uk_conversation_runtime_owner
        UNIQUE (
            organization_id, team_id, workspace_id, id,
            owner_member_id, owner_principal_id, personal_agent_principal_id
        ),
    CONSTRAINT fk_conversation_workspace
        FOREIGN KEY (organization_id, team_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_owner_member
        FOREIGN KEY (organization_id, team_id, owner_member_id, owner_principal_id)
        REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_personal_agent
        FOREIGN KEY (organization_id, team_id, personal_agent_principal_id)
        REFERENCES crewscope.principal (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_title CHECK (BTRIM(title) <> ''),
    CONSTRAINT ck_conversation_visibility CHECK (visibility IN ('PRIVATE', 'TEAM')),
    CONSTRAINT ck_conversation_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_conversation_sequence CHECK (
        last_message_sequence IS NULL OR last_message_sequence > 0
    ),
    CONSTRAINT ck_conversation_owner_audit CHECK (
        created_by_principal_id = owner_principal_id
    ),
    CONSTRAINT ck_conversation_version CHECK (version >= 0),
    CONSTRAINT ck_conversation_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_conversation_team_updated
    ON crewscope.conversation (
        organization_id, team_id, updated_at DESC, id DESC
    );

CREATE INDEX ix_conversation_owner_updated
    ON crewscope.conversation (
        organization_id, team_id, owner_member_id, updated_at DESC, id DESC
    );

CREATE TABLE crewscope.conversation_participant (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    principal_id UUID NOT NULL,
    team_member_id UUID,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    joined_by_principal_id UUID NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_conversation_participant_scope_id
        UNIQUE (organization_id, team_id, workspace_id, conversation_id, id),
    CONSTRAINT uk_conversation_participant_author
        UNIQUE (
            organization_id, team_id, workspace_id,
            conversation_id, id, principal_id
        ),
    CONSTRAINT fk_conversation_participant_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, conversation_id)
        REFERENCES crewscope.conversation (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_participant_principal
        FOREIGN KEY (organization_id, principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_participant_member
        FOREIGN KEY (organization_id, team_id, team_member_id, principal_id)
        REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_participant_joined_by
        FOREIGN KEY (organization_id, joined_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_participant_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_participant_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_participant_role CHECK (
        role IN ('OWNER', 'MEMBER', 'AGENT')
    ),
    CONSTRAINT ck_conversation_participant_member_shape CHECK (
        (role IN ('OWNER', 'MEMBER') AND team_member_id IS NOT NULL)
        OR (role = 'AGENT' AND team_member_id IS NULL)
    ),
    CONSTRAINT ck_conversation_participant_status CHECK (status IN ('ACTIVE', 'LEFT')),
    CONSTRAINT ck_conversation_participant_lifecycle CHECK (
        (status = 'ACTIVE' AND left_at IS NULL)
        OR (status = 'LEFT' AND role = 'MEMBER' AND left_at IS NOT NULL)
    ),
    CONSTRAINT ck_conversation_participant_times CHECK (
        joined_at >= created_at
        AND (left_at IS NULL OR left_at >= joined_at)
        AND updated_at >= created_at
    ),
    CONSTRAINT ck_conversation_participant_audit CHECK (
        joined_by_principal_id = created_by_principal_id
    ),
    CONSTRAINT ck_conversation_participant_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX ux_conversation_participant_active
    ON crewscope.conversation_participant (conversation_id, principal_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_conversation_participant_principal_status
    ON crewscope.conversation_participant (
        organization_id, principal_id, status, joined_at, id
    );

-- Message content and creation provenance are immutable. A later withdrawal or redaction changes
-- only the explicit moderation columns; AuditEvent records the moderation reason and authorization.
CREATE TABLE crewscope.message (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    participant_id UUID,
    author_principal_id UUID,
    content_markdown TEXT NOT NULL,
    client_message_key VARCHAR(200),
    moderation_status VARCHAR(32) NOT NULL DEFAULT 'VISIBLE',
    moderated_at TIMESTAMPTZ,
    moderated_by_principal_id UUID,
    moderation_reason_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_message_scope_id
        UNIQUE (organization_id, team_id, workspace_id, conversation_id, id),
    CONSTRAINT uk_message_conversation_sequence UNIQUE (conversation_id, sequence),
    CONSTRAINT fk_message_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, conversation_id)
        REFERENCES crewscope.conversation (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_message_participant_author
        FOREIGN KEY (
            organization_id, team_id, workspace_id,
            conversation_id, participant_id, author_principal_id
        ) REFERENCES crewscope.conversation_participant (
            organization_id, team_id, workspace_id,
            conversation_id, id, principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_message_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_moderated_by
        FOREIGN KEY (organization_id, moderated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_message_sequence CHECK (sequence > 0),
    CONSTRAINT ck_message_type CHECK (
        message_type IN ('USER_MESSAGE', 'AGENT_MESSAGE', 'SYSTEM_NOTICE')
    ),
    CONSTRAINT ck_message_author_shape CHECK (
        (message_type = 'SYSTEM_NOTICE'
            AND participant_id IS NULL
            AND author_principal_id IS NULL)
        OR (message_type <> 'SYSTEM_NOTICE'
            AND participant_id IS NOT NULL
            AND author_principal_id IS NOT NULL
            AND author_principal_id = created_by_principal_id)
    ),
    CONSTRAINT ck_message_content CHECK (
        BTRIM(content_markdown) <> '' AND CHAR_LENGTH(content_markdown) <= 50000
    ),
    CONSTRAINT ck_message_client_key CHECK (
        client_message_key IS NULL OR BTRIM(client_message_key) <> ''
    ),
    CONSTRAINT ck_message_moderation_status CHECK (
        moderation_status IN ('VISIBLE', 'WITHDRAWN', 'REDACTED')
    ),
    CONSTRAINT ck_message_moderation_shape CHECK (
        (moderation_status = 'VISIBLE'
            AND moderated_at IS NULL
            AND moderated_by_principal_id IS NULL
            AND moderation_reason_code IS NULL)
        OR (moderation_status IN ('WITHDRAWN', 'REDACTED')
            AND moderated_at IS NOT NULL
            AND moderated_by_principal_id IS NOT NULL
            AND moderation_reason_code IS NOT NULL
            AND BTRIM(moderation_reason_code) <> '')
    ),
    CONSTRAINT ck_message_moderation_time CHECK (
        moderated_at IS NULL OR moderated_at >= created_at
    ),
    CONSTRAINT ck_message_immutable_audit CHECK (
        updated_at = created_at AND updated_by_principal_id = created_by_principal_id
    )
);

CREATE UNIQUE INDEX ux_message_client_key
    ON crewscope.message (conversation_id, client_message_key)
    WHERE client_message_key IS NOT NULL;

CREATE INDEX ix_message_conversation_history
    ON crewscope.message (conversation_id, sequence DESC, id DESC);

CREATE TABLE crewscope.task_intent (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    proposed_by_principal_id UUID NOT NULL,
    proposal_revision INTEGER NOT NULL,
    work_project_id UUID NOT NULL,
    objective TEXT NOT NULL,
    acceptance_criteria JSONB NOT NULL,
    owner_principal_id UUID NOT NULL,
    owner_principal_type VARCHAR(32) NOT NULL,
    owner_member_id UUID NOT NULL,
    executor_principal_id UUID,
    executor_principal_type VARCHAR(32),
    executor_member_id UUID,
    gate_reviewer_principal_id UUID,
    gate_reviewer_principal_type VARCHAR(32),
    gate_reviewer_member_id UUID,
    status VARCHAR(32) NOT NULL,
    decided_by_principal_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    confirmed_work_item_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_task_intent_scope_id
        UNIQUE (organization_id, team_id, workspace_id, conversation_id, id),
    CONSTRAINT fk_task_intent_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, conversation_id)
        REFERENCES crewscope.conversation (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_project
        FOREIGN KEY (organization_id, team_id, workspace_id, work_project_id)
        REFERENCES crewscope.work_project (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_proposer
        FOREIGN KEY (organization_id, proposed_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_owner
        FOREIGN KEY (organization_id, owner_principal_id, owner_principal_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_owner_member
        FOREIGN KEY (organization_id, team_id, owner_member_id, owner_principal_id)
        REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_executor
        FOREIGN KEY (organization_id, executor_principal_id, executor_principal_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_executor_member
        FOREIGN KEY (organization_id, team_id, executor_member_id, executor_principal_id)
        REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_gate_reviewer
        FOREIGN KEY (
            organization_id, gate_reviewer_principal_id, gate_reviewer_principal_type
        ) REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_gate_reviewer_member
        FOREIGN KEY (
            organization_id, team_id,
            gate_reviewer_member_id, gate_reviewer_principal_id
        ) REFERENCES crewscope.team_member (
            organization_id, team_id, id, user_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_decided_by
        FOREIGN KEY (organization_id, decided_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_confirmed_work_item
        FOREIGN KEY (
            organization_id, team_id, workspace_id,
            work_project_id, confirmed_work_item_id
        ) REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_intent_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_intent_revision CHECK (proposal_revision > 0),
    CONSTRAINT ck_task_intent_objective CHECK (
        BTRIM(objective) <> '' AND CHAR_LENGTH(objective) <= 5000
    ),
    CONSTRAINT ck_task_intent_acceptance CHECK (
        JSONB_TYPEOF(acceptance_criteria) = 'array'
        AND JSONB_ARRAY_LENGTH(acceptance_criteria) BETWEEN 1 AND 20
    ),
    CONSTRAINT ck_task_intent_owner_shape CHECK (
        owner_principal_type = 'USER'
    ),
    CONSTRAINT ck_task_intent_executor_shape CHECK (
        (executor_principal_id IS NULL
            AND executor_principal_type IS NULL
            AND executor_member_id IS NULL)
        OR (executor_principal_id IS NOT NULL
            AND executor_principal_type IN (
                'USER', 'PERSONAL_AGENT', 'TEAM_AGENT', 'SPECIALIST_AGENT'
            )
            AND ((executor_principal_type = 'USER') = (executor_member_id IS NOT NULL)))
    ),
    CONSTRAINT ck_task_intent_gate_reviewer_shape CHECK (
        (gate_reviewer_principal_id IS NULL
            AND gate_reviewer_principal_type IS NULL
            AND gate_reviewer_member_id IS NULL)
        OR (gate_reviewer_principal_id IS NOT NULL
            AND gate_reviewer_principal_type = 'USER'
            AND gate_reviewer_member_id IS NOT NULL)
    ),
    CONSTRAINT ck_task_intent_duty_separation CHECK (
        gate_reviewer_principal_id IS NULL
        OR (gate_reviewer_principal_id <> owner_principal_id
            AND gate_reviewer_principal_id IS DISTINCT FROM executor_principal_id)
    ),
    CONSTRAINT ck_task_intent_status CHECK (
        status IN ('DRAFT', 'READY', 'CONFIRMED', 'REJECTED', 'EXPIRED')
    ),
    CONSTRAINT ck_task_intent_decision CHECK (
        (status IN ('DRAFT', 'READY')
            AND decided_by_principal_id IS NULL
            AND decided_at IS NULL
            AND decision_reason IS NULL
            AND confirmed_work_item_id IS NULL)
        OR (status = 'CONFIRMED'
            AND decided_by_principal_id IS NOT NULL
            AND decided_at IS NOT NULL
            AND decision_reason IS NULL
            AND confirmed_work_item_id IS NOT NULL)
        OR (status IN ('REJECTED', 'EXPIRED')
            AND decided_by_principal_id IS NOT NULL
            AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL
            AND BTRIM(decision_reason) <> ''
            AND CHAR_LENGTH(decision_reason) <= 1000
            AND confirmed_work_item_id IS NULL)
    ),
    CONSTRAINT ck_task_intent_audit CHECK (
        created_by_principal_id = proposed_by_principal_id
        AND (decided_by_principal_id IS NULL
            OR updated_by_principal_id = decided_by_principal_id)
    ),
    CONSTRAINT ck_task_intent_decision_time CHECK (
        decided_at IS NULL OR decided_at >= created_at
    ),
    CONSTRAINT ck_task_intent_version CHECK (version >= 0),
    CONSTRAINT ck_task_intent_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_task_intent_confirmed_work_item
    ON crewscope.task_intent (confirmed_work_item_id)
    WHERE confirmed_work_item_id IS NOT NULL;

CREATE INDEX ix_task_intent_conversation_status
    ON crewscope.task_intent (
        conversation_id, status, updated_at DESC, id DESC
    );

CREATE TABLE crewscope.conversation_work_item_link (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    work_project_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    origin VARCHAR(32) NOT NULL,
    created_by_principal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation_work_item_link_scope_id
        UNIQUE (
            organization_id, team_id, workspace_id,
            conversation_id, work_item_id, id
        ),
    CONSTRAINT uk_conversation_work_item_pair UNIQUE (conversation_id, work_item_id),
    CONSTRAINT fk_conversation_work_item_link_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, conversation_id)
        REFERENCES crewscope.conversation (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_work_item_link_work_item
        FOREIGN KEY (
            organization_id, team_id, workspace_id, work_project_id, work_item_id
        ) REFERENCES crewscope.work_item (
            organization_id, team_id, workspace_id, project_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_work_item_link_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_work_item_link_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_work_item_link_origin CHECK (
        origin IN ('TASK_INTENT_CONFIRMATION', 'MANUAL', 'WORK_ITEM_DISCUSSION')
    ),
    CONSTRAINT ck_conversation_work_item_link_immutable_audit CHECK (
        updated_at = created_at
        AND updated_by_principal_id = created_by_principal_id
    )
);

CREATE INDEX ix_conversation_work_item_link_work_item
    ON crewscope.conversation_work_item_link (
        work_item_id, created_at, id
    );

CREATE TABLE crewscope.agent_runtime_session (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    owner_member_id UUID NOT NULL,
    owner_principal_id UUID NOT NULL,
    personal_agent_principal_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    agent_profile_version BIGINT NOT NULL,
    agent_scope_user_id VARCHAR(500) NOT NULL,
    agent_scope_session_id VARCHAR(500) NOT NULL,
    state_reference VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_agent_runtime_session_scope_id
        UNIQUE (organization_id, team_id, workspace_id, conversation_id, id),
    CONSTRAINT uk_agent_runtime_session_agentscope_key
        UNIQUE (agent_scope_user_id, agent_scope_session_id),
    CONSTRAINT uk_agent_runtime_session_state_reference UNIQUE (state_reference),
    CONSTRAINT fk_agent_runtime_session_conversation
        FOREIGN KEY (
            organization_id, team_id, workspace_id, conversation_id,
            owner_member_id, owner_principal_id, personal_agent_principal_id
        ) REFERENCES crewscope.conversation (
            organization_id, team_id, workspace_id, id,
            owner_member_id, owner_principal_id, personal_agent_principal_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runtime_session_profile
        FOREIGN KEY (
            organization_id, team_id, workspace_id, agent_profile_id,
            personal_agent_principal_id, owner_member_id
        ) REFERENCES crewscope.agent_profile (
            organization_id, team_id, workspace_id, id,
            agent_principal_id, owner_member_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runtime_session_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_runtime_session_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_runtime_session_profile_version CHECK (agent_profile_version >= 0),
    CONSTRAINT ck_agent_runtime_session_user_key CHECK (
        agent_scope_user_id LIKE 'crewscope:v1:user:%'
    ),
    CONSTRAINT ck_agent_runtime_session_session_key CHECK (
        agent_scope_session_id LIKE 'crewscope:v1:session:%'
    ),
    CONSTRAINT ck_agent_runtime_session_state_reference CHECK (
        state_reference = 'crewscope:agent-state:v1:' || id::TEXT
    ),
    CONSTRAINT ck_agent_runtime_session_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_agent_runtime_session_version CHECK (version >= 0),
    CONSTRAINT ck_agent_runtime_session_timestamps CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX ux_agent_runtime_session_active_binding
    ON crewscope.agent_runtime_session (
        conversation_id, owner_member_id, personal_agent_principal_id
    ) WHERE status = 'ACTIVE';

CREATE INDEX ix_agent_runtime_session_owner_status
    ON crewscope.agent_runtime_session (
        organization_id, team_id, owner_member_id, status, updated_at DESC
    );

CREATE TABLE crewscope.provider_definition (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    provider_key VARCHAR(100) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    interface_version VARCHAR(64) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    capabilities JSONB NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_provider_definition_key UNIQUE (organization_id, provider_key),
    CONSTRAINT uk_provider_definition_interface
        UNIQUE (organization_id, id, provider_type, interface_version),
    CONSTRAINT uk_provider_definition_scope_type
        UNIQUE (organization_id, id, provider_type),
    CONSTRAINT fk_provider_definition_organization
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_definition_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_definition_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_provider_definition_key CHECK (BTRIM(provider_key) <> ''),
    CONSTRAINT ck_provider_definition_type CHECK (
        provider_type IN (
            'WORK_ITEM', 'SOURCE_CODE', 'COLLABORATION', 'CI_CD',
            'OBSERVABILITY', 'KNOWLEDGE', 'SANDBOX', 'IDENTITY'
        )
    ),
    CONSTRAINT ck_provider_definition_interface_version CHECK (
        BTRIM(interface_version) <> ''
    ),
    CONSTRAINT ck_provider_definition_display_name CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT ck_provider_definition_capabilities CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) > 0
    ),
    CONSTRAINT ck_provider_definition_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_provider_definition_version CHECK (version >= 0),
    CONSTRAINT ck_provider_definition_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_provider_definition_type_status
    ON crewscope.provider_definition (
        organization_id, provider_type, status, provider_key
    );

CREATE TABLE crewscope.provider_implementation (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    provider_definition_id UUID NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    definition_interface_version VARCHAR(64) NOT NULL,
    implementation_key VARCHAR(100) NOT NULL,
    implementation_version VARCHAR(64) NOT NULL,
    capabilities JSONB NOT NULL,
    connection_requirement VARCHAR(16) NOT NULL,
    connector_key VARCHAR(100),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_provider_implementation_key
        UNIQUE (organization_id, provider_definition_id, implementation_key),
    CONSTRAINT uk_provider_implementation_binding_identity
        UNIQUE (
            organization_id, id,
            provider_definition_id, provider_type, connection_requirement
        ),
    CONSTRAINT fk_provider_implementation_definition
        FOREIGN KEY (
            organization_id, provider_definition_id,
            provider_type, definition_interface_version
        ) REFERENCES crewscope.provider_definition (
            organization_id, id, provider_type, interface_version
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_implementation_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_implementation_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_provider_implementation_key CHECK (BTRIM(implementation_key) <> ''),
    CONSTRAINT ck_provider_implementation_version CHECK (
        BTRIM(implementation_version) <> ''
    ),
    CONSTRAINT ck_provider_implementation_capabilities CHECK (
        JSONB_TYPEOF(capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(capabilities) > 0
    ),
    CONSTRAINT ck_provider_implementation_connection CHECK (
        (connection_requirement = 'NONE' AND connector_key IS NULL)
        OR (connection_requirement = 'REQUIRED'
            AND connector_key IS NOT NULL
            AND BTRIM(connector_key) <> '')
    ),
    CONSTRAINT ck_provider_implementation_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_provider_implementation_version_number CHECK (version >= 0),
    CONSTRAINT ck_provider_implementation_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_provider_implementation_definition_status
    ON crewscope.provider_implementation (
        organization_id, provider_definition_id, status, implementation_key
    );

CREATE TABLE crewscope.connection (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    owner_id UUID NOT NULL,
    owner_team_id UUID,
    owner_user_principal_id UUID,
    connector_key VARCHAR(100) NOT NULL,
    external_account_reference VARCHAR(500) NOT NULL,
    credential_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ,
    terminal_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_connection_owner
        UNIQUE (organization_id, id, owner_type, owner_id),
    CONSTRAINT uk_connection_scope_id
        UNIQUE (organization_id, id),
    CONSTRAINT fk_connection_organization
        FOREIGN KEY (organization_id)
        REFERENCES crewscope.organization (id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_owner_team
        FOREIGN KEY (organization_id, owner_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_owner_user
        FOREIGN KEY (organization_id, owner_user_principal_id, owner_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_connection_credential
        FOREIGN KEY (organization_id, credential_id)
        REFERENCES crewscope.credential_secret (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_connection_owner CHECK (
        (owner_type = 'ORGANIZATION'
            AND owner_id = organization_id
            AND owner_team_id IS NULL
            AND owner_user_principal_id IS NULL)
        OR (owner_type = 'TEAM'
            AND owner_id = owner_team_id
            AND owner_team_id IS NOT NULL
            AND owner_user_principal_id IS NULL)
        OR (owner_type = 'USER'
            AND owner_id = owner_user_principal_id
            AND owner_team_id IS NULL
            AND owner_user_principal_id IS NOT NULL)
    ),
    CONSTRAINT ck_connection_connector CHECK (BTRIM(connector_key) <> ''),
    CONSTRAINT ck_connection_external_account CHECK (
        BTRIM(external_account_reference) <> ''
    ),
    CONSTRAINT ck_connection_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_connection_terminal CHECK (
        (status IN ('ACTIVE', 'SUSPENDED') AND terminal_reason IS NULL)
        OR (status IN ('REVOKED', 'EXPIRED')
            AND terminal_reason IS NOT NULL
            AND BTRIM(terminal_reason) <> '')
    ),
    CONSTRAINT ck_connection_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_connection_version CHECK (version >= 0),
    CONSTRAINT ck_connection_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_connection_owner_status
    ON crewscope.connection (
        organization_id, owner_type, owner_id, status, updated_at DESC
    );

CREATE INDEX ix_connection_connector_status
    ON crewscope.connection (organization_id, connector_key, status, expires_at);

CREATE TABLE crewscope.connection_grant (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    connection_owner_type VARCHAR(32) NOT NULL,
    connection_owner_id UUID NOT NULL,
    grantee_type VARCHAR(32) NOT NULL,
    grantee_id UUID NOT NULL,
    grantee_team_id UUID,
    grantee_user_principal_id UUID,
    granted_capabilities JSONB NOT NULL,
    resource_unrestricted BOOLEAN NOT NULL,
    granted_resources JSONB NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL,
    terminal_reason VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_connection_grant_grantee
        UNIQUE (organization_id, id, connection_id, grantee_type, grantee_id),
    CONSTRAINT fk_connection_grant_connection_owner
        FOREIGN KEY (
            organization_id, connection_id,
            connection_owner_type, connection_owner_id
        ) REFERENCES crewscope.connection (
            organization_id, id, owner_type, owner_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_grant_grantee_team
        FOREIGN KEY (organization_id, grantee_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_grant_grantee_user
        FOREIGN KEY (organization_id, grantee_user_principal_id, grantee_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_connection_grant_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_connection_grant_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_connection_grant_grantee CHECK (
        (grantee_type = 'ORGANIZATION'
            AND grantee_id = organization_id
            AND grantee_team_id IS NULL
            AND grantee_user_principal_id IS NULL)
        OR (grantee_type = 'TEAM'
            AND grantee_id = grantee_team_id
            AND grantee_team_id IS NOT NULL
            AND grantee_user_principal_id IS NULL)
        OR (grantee_type = 'USER'
            AND grantee_id = grantee_user_principal_id
            AND grantee_team_id IS NULL
            AND grantee_user_principal_id IS NOT NULL)
    ),
    CONSTRAINT ck_connection_grant_delegation CHECK (
        (connection_owner_type = grantee_type AND connection_owner_id = grantee_id)
        OR connection_owner_type = 'ORGANIZATION'
    ),
    CONSTRAINT ck_connection_grant_capabilities CHECK (
        JSONB_TYPEOF(granted_capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(granted_capabilities) > 0
    ),
    CONSTRAINT ck_connection_grant_resources CHECK (
        JSONB_TYPEOF(granted_resources) = 'array'
        AND ((resource_unrestricted AND JSONB_ARRAY_LENGTH(granted_resources) = 0)
            OR (NOT resource_unrestricted AND JSONB_ARRAY_LENGTH(granted_resources) > 0))
    ),
    CONSTRAINT ck_connection_grant_validity CHECK (
        expires_at IS NULL OR expires_at > valid_from
    ),
    CONSTRAINT ck_connection_grant_status CHECK (
        status IN ('ACTIVE', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_connection_grant_terminal CHECK (
        (status = 'ACTIVE' AND terminal_reason IS NULL)
        OR (status IN ('REVOKED', 'EXPIRED')
            AND terminal_reason IS NOT NULL
            AND BTRIM(terminal_reason) <> '')
    ),
    CONSTRAINT ck_connection_grant_version CHECK (version >= 0),
    CONSTRAINT ck_connection_grant_timestamps CHECK (updated_at >= created_at)
);

CREATE INDEX ix_connection_grant_grantee_status
    ON crewscope.connection_grant (
        organization_id, grantee_type, grantee_id, status, expires_at
    );

CREATE INDEX ix_connection_grant_connection_status
    ON crewscope.connection_grant (connection_id, status, expires_at);

CREATE TABLE crewscope.provider_binding (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    work_project_id UUID,
    owner_type VARCHAR(32) NOT NULL,
    owner_id UUID NOT NULL,
    owner_team_id UUID,
    owner_user_principal_id UUID,
    provider_definition_id UUID NOT NULL,
    provider_definition_version BIGINT NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    provider_implementation_id UUID NOT NULL,
    provider_implementation_version BIGINT NOT NULL,
    connection_requirement VARCHAR(16) NOT NULL,
    connection_id UUID,
    connection_version BIGINT,
    connection_grant_id UUID,
    connection_grant_version BIGINT,
    execution_identity VARCHAR(64),
    effective_capabilities JSONB NOT NULL,
    resource_unrestricted BOOLEAN NOT NULL,
    effective_resources JSONB NOT NULL,
    default_usage BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_principal_id UUID NOT NULL,
    CONSTRAINT uk_provider_binding_scope_id
        UNIQUE (organization_id, team_id, workspace_id, id),
    CONSTRAINT fk_provider_binding_workspace
        FOREIGN KEY (organization_id, team_id, workspace_id)
        REFERENCES crewscope.workspace (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_project
        FOREIGN KEY (organization_id, team_id, workspace_id, work_project_id)
        REFERENCES crewscope.work_project (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_owner_team
        FOREIGN KEY (organization_id, owner_team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_owner_user
        FOREIGN KEY (organization_id, owner_user_principal_id, owner_type)
        REFERENCES crewscope.principal (organization_id, id, principal_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_definition
        FOREIGN KEY (
            organization_id, provider_definition_id, provider_type
        ) REFERENCES crewscope.provider_definition (
            organization_id, id, provider_type
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_implementation
        FOREIGN KEY (
            organization_id, provider_implementation_id,
            provider_definition_id, provider_type, connection_requirement
        ) REFERENCES crewscope.provider_implementation (
            organization_id, id,
            provider_definition_id, provider_type, connection_requirement
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_connection
        FOREIGN KEY (organization_id, connection_id)
        REFERENCES crewscope.connection (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_grant
        FOREIGN KEY (
            organization_id, connection_grant_id, connection_id,
            owner_type, owner_id
        ) REFERENCES crewscope.connection_grant (
            organization_id, id, connection_id,
            grantee_type, grantee_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_created_by
        FOREIGN KEY (organization_id, created_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_provider_binding_updated_by
        FOREIGN KEY (organization_id, updated_by_principal_id)
        REFERENCES crewscope.principal (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_provider_binding_target CHECK (
        (target_type = 'WORKSPACE' AND work_project_id IS NULL)
        OR (target_type = 'WORK_PROJECT' AND work_project_id IS NOT NULL)
    ),
    CONSTRAINT ck_provider_binding_owner CHECK (
        (owner_type = 'ORGANIZATION'
            AND owner_id = organization_id
            AND owner_team_id IS NULL
            AND owner_user_principal_id IS NULL)
        OR (owner_type = 'TEAM'
            AND owner_id = owner_team_id
            AND owner_team_id = team_id
            AND owner_user_principal_id IS NULL)
        OR (owner_type = 'USER'
            AND owner_id = owner_user_principal_id
            AND owner_team_id IS NULL
            AND owner_user_principal_id IS NOT NULL)
    ),
    CONSTRAINT ck_provider_binding_definition_version CHECK (
        provider_definition_version >= 0
    ),
    CONSTRAINT ck_provider_binding_implementation_version CHECK (
        provider_implementation_version >= 0
    ),
    CONSTRAINT ck_provider_binding_external_versions CHECK (
        (connection_version IS NULL AND connection_grant_version IS NULL)
        OR (connection_version >= 0 AND connection_grant_version >= 0)
    ),
    CONSTRAINT ck_provider_binding_connection_shape CHECK (
        (connection_requirement = 'NONE'
            AND connection_id IS NULL
            AND connection_version IS NULL
            AND connection_grant_id IS NULL
            AND connection_grant_version IS NULL
            AND execution_identity IS NULL)
        OR (connection_requirement = 'REQUIRED'
            AND connection_id IS NOT NULL
            AND connection_version IS NOT NULL
            AND connection_grant_id IS NOT NULL
            AND connection_grant_version IS NOT NULL
            AND execution_identity IN (
                'DELEGATED_USER',
                'TEAM_SERVICE_ACCOUNT',
                'ORGANIZATION_SERVICE_ACCOUNT'
            ))
    ),
    CONSTRAINT ck_provider_binding_access CHECK (
        JSONB_TYPEOF(effective_capabilities) = 'array'
        AND JSONB_ARRAY_LENGTH(effective_capabilities) > 0
        AND JSONB_TYPEOF(effective_resources) = 'array'
        AND ((resource_unrestricted AND JSONB_ARRAY_LENGTH(effective_resources) = 0)
            OR (NOT resource_unrestricted AND JSONB_ARRAY_LENGTH(effective_resources) > 0))
    ),
    CONSTRAINT ck_provider_binding_status CHECK (
        status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')
    ),
    CONSTRAINT ck_provider_binding_version CHECK (version >= 0),
    CONSTRAINT ck_provider_binding_timestamps CHECK (updated_at >= created_at)
);

-- One explicit default is allowed at an exact resolution level. Multiple non-default candidates
-- remain valid so BindingResolver can report same-level ambiguity instead of silently choosing.
CREATE UNIQUE INDEX ux_provider_binding_active_default
    ON crewscope.provider_binding (
        organization_id, team_id, workspace_id, target_type,
        COALESCE(work_project_id, '00000000-0000-0000-0000-000000000000'::UUID),
        owner_type, owner_id, provider_type
    ) WHERE status = 'ACTIVE' AND default_usage;

CREATE INDEX ix_provider_binding_resolver
    ON crewscope.provider_binding (
        organization_id, team_id, workspace_id,
        provider_type, status, owner_type, owner_id, target_type
    );

CREATE INDEX ix_provider_binding_project
    ON crewscope.provider_binding (
        organization_id, team_id, work_project_id,
        provider_type, status, owner_type, owner_id
    ) WHERE work_project_id IS NOT NULL;
