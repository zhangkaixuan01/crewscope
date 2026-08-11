-- Durable Conversation Event positions bridge committed DomainEvents to resumable HTTP/SSE feeds.
-- The index is a rebuildable projection; domain_event remains the canonical business fact store.
CREATE SEQUENCE crewscope.conversation_event_position_seq AS BIGINT;

CREATE TABLE crewscope.conversation_event (
    position BIGINT PRIMARY KEY
        DEFAULT nextval('crewscope.conversation_event_position_seq'),
    event_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    domain_event_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation_event_stream_event
        UNIQUE (organization_id, event_id),
    CONSTRAINT uk_conversation_event_domain_event
        UNIQUE (organization_id, conversation_id, domain_event_id),
    CONSTRAINT fk_conversation_event_conversation
        FOREIGN KEY (organization_id, team_id, workspace_id, conversation_id)
        REFERENCES crewscope.conversation (
            organization_id, team_id, workspace_id, id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_event_domain_event
        FOREIGN KEY (organization_id, domain_event_id)
        REFERENCES crewscope.domain_event (organization_id, event_id) ON DELETE RESTRICT,
    CONSTRAINT ck_conversation_event_position CHECK (position > 0)
);

ALTER SEQUENCE crewscope.conversation_event_position_seq
    OWNED BY crewscope.conversation_event.position;

CREATE INDEX ix_conversation_event_stream_position
    ON crewscope.conversation_event (
        organization_id, team_id, conversation_id, position
    );

-- V7 events predate this projection. Direct Conversation aggregates and participant payloads
-- contain enough information to build the initial stream without changing the source facts.
WITH projected AS (
    SELECT
        event.event_id AS domain_event_id,
        event.organization_id,
        conversation.team_id,
        conversation.workspace_id,
        conversation.id AS conversation_id,
        event.occurred_at,
        md5(
            'CREWSCOPE:REALTIME:CONVERSATION:' || event.event_id::TEXT
        )::UUID AS stream_event_id
    FROM crewscope.domain_event event
    JOIN crewscope.conversation conversation
      ON conversation.organization_id = event.organization_id
     AND conversation.team_id = event.team_id
     AND (
            (event.subject_type = 'CONVERSATION'
             AND event.subject_id = conversation.id)
         OR event.payload ->> 'conversationId' = conversation.id::TEXT
     )
), ordered AS (
    SELECT
        ROW_NUMBER() OVER (ORDER BY occurred_at, domain_event_id) AS position,
        stream_event_id,
        organization_id,
        team_id,
        workspace_id,
        conversation_id,
        domain_event_id,
        occurred_at
    FROM projected
)
INSERT INTO crewscope.conversation_event (
    position, event_id,
    organization_id, team_id, workspace_id, conversation_id,
    domain_event_id, occurred_at, created_at
)
SELECT
    position, stream_event_id,
    organization_id, team_id, workspace_id, conversation_id,
    domain_event_id, occurred_at, occurred_at
FROM ordered
ORDER BY position;

SELECT setval(
    'crewscope.conversation_event_position_seq',
    COALESCE(MAX(position), 1),
    MAX(position) IS NOT NULL
)
FROM crewscope.conversation_event;
