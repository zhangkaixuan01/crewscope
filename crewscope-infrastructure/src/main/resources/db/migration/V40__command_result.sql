-- No backfill: old receipts do not reliably identify an actor and result coordinate.
-- Results and receipts are committed together; no TTL or cascaded deletion is introduced.
ALTER TABLE crewscope.command_receipt ADD CONSTRAINT uk_command_receipt_result_identity
    UNIQUE (organization_id, idempotency_key, command_id, command_type);

CREATE TABLE crewscope.command_result (
    organization_id UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    command_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    command_type VARCHAR(100) NOT NULL,
    team_id UUID NOT NULL,
    project_id UUID,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    resource_version BIGINT NOT NULL CHECK (resource_version >= 0),
    stage VARCHAR(32) NOT NULL DEFAULT 'COMMITTED' CHECK (stage = 'COMMITTED'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, idempotency_key),
    CONSTRAINT uk_command_result_command UNIQUE (organization_id, command_id),
    CONSTRAINT fk_command_result_receipt
        FOREIGN KEY (organization_id, idempotency_key, command_id, command_type)
        REFERENCES crewscope.command_receipt (organization_id, idempotency_key, command_id, command_type),
    CONSTRAINT fk_command_result_actor FOREIGN KEY (organization_id, actor_id)
        REFERENCES crewscope.principal (organization_id, id),
    CONSTRAINT fk_command_result_team FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id),
    CONSTRAINT fk_command_result_project FOREIGN KEY (organization_id, team_id, project_id)
        REFERENCES crewscope.work_project (organization_id, team_id, id),
    CONSTRAINT ck_command_result_coordinate CHECK (
        (resource_type = 'WORK_PROJECT' AND project_id IS NOT NULL AND project_id = resource_id)
        OR (resource_type = 'WORK_ITEM' AND project_id IS NOT NULL)
        OR (resource_type = 'CONVERSATION' AND project_id IS NULL)
    ),
    CONSTRAINT ck_command_result_resource CHECK (resource_id <> '00000000-0000-0000-0000-000000000000')
);

CREATE FUNCTION crewscope.reject_command_result_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Command results are immutable';
END;
$$;
CREATE TRIGGER command_result_immutable
    BEFORE UPDATE OR DELETE ON crewscope.command_result
    FOR EACH ROW EXECUTE FUNCTION crewscope.reject_command_result_mutation();
