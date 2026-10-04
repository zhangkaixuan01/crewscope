-- M10-I02a: agent assistant memory lifecycle (pure PostgreSQL, no vector).
-- Two tables back the S01 §3.6 contract: one owner row per (member x Agent) carrying the
-- monotonic clearance generation, and one entry row per named preference value inside the
-- five-part owner key (org + team + agent profile + owner principal + policyId@version).
-- The policy columns deliberately have no FK: policies are a code catalog
-- (AgentMemoryPolicyCatalog), not a table; an unresolvable reference surfaces as an explicit
-- "policy unavailable" degradation, never a silent drop. Old-generation rows stay invisible
-- (reads join on the owner's current generation) until the sweeper deletes them; nothing is
-- resurrected. The 1KB value bound counts UTF-8 bytes (octet_length), not characters.

CREATE TABLE crewscope.agent_memory_owner (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    owner_principal_id UUID NOT NULL,
    clearance_generation BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_memory_owner_scope
        UNIQUE (organization_id, team_id, agent_profile_id, owner_principal_id),
    CONSTRAINT fk_agent_memory_owner_team
        FOREIGN KEY (organization_id, team_id)
        REFERENCES crewscope.team (organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_memory_owner_profile
        FOREIGN KEY (organization_id, team_id, agent_profile_id)
        REFERENCES crewscope.agent_profile (organization_id, team_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_memory_owner_principal
        FOREIGN KEY (owner_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    -- Starts at 0 and only ever moves forward via clear (+1).
    CONSTRAINT ck_agent_memory_owner_generation CHECK (clearance_generation >= 0),
    CONSTRAINT ck_agent_memory_owner_timestamps CHECK (updated_at >= created_at)
);

CREATE TABLE crewscope.agent_memory_entry (
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    team_id UUID NOT NULL,
    agent_profile_id UUID NOT NULL,
    owner_principal_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    memory_key VARCHAR(64) NOT NULL,
    value TEXT NOT NULL,
    clearance_generation BIGINT NOT NULL,
    version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_principal_id UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_principal_id UUID NOT NULL,
    PRIMARY KEY (id),
    -- The policy version completes the five-part owner key: switching policy versions opens
    -- a new space, and the same memory_key may coexist in both until the old space is swept.
    CONSTRAINT uk_agent_memory_entry_space_key
        UNIQUE (organization_id, team_id, agent_profile_id, owner_principal_id,
                policy_id, policy_version, memory_key),
    CONSTRAINT fk_agent_memory_entry_owner
        FOREIGN KEY (organization_id, team_id, agent_profile_id, owner_principal_id)
        REFERENCES crewscope.agent_memory_owner (
            organization_id, team_id, agent_profile_id, owner_principal_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_agent_memory_entry_created_by
        FOREIGN KEY (created_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_memory_entry_updated_by
        FOREIGN KEY (updated_by_principal_id)
        REFERENCES crewscope.principal (id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_memory_entry_key CHECK (memory_key ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    -- Byte-precise 1KB frozen structure bound (octets, not characters).
    CONSTRAINT ck_agent_memory_entry_value CHECK (
        BTRIM(value) <> '' AND octet_length(value) <= 1024),
    CONSTRAINT ck_agent_memory_entry_policy_version CHECK (policy_version >= 1),
    CONSTRAINT ck_agent_memory_entry_generation CHECK (clearance_generation >= 0),
    CONSTRAINT ck_agent_memory_entry_version CHECK (version >= 0),
    CONSTRAINT ck_agent_memory_entry_timestamps CHECK (updated_at >= created_at)
);

-- Generation-gated reads and the TTL/stale-generation sweep paths.
CREATE INDEX ix_agent_memory_entry_owner_generation
    ON crewscope.agent_memory_entry (
        organization_id, team_id, agent_profile_id, owner_principal_id, clearance_generation);

CREATE INDEX ix_agent_memory_entry_expires
    ON crewscope.agent_memory_entry (expires_at);
