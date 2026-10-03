-- M10-I01c: Team control-plane listing keyset (pure PostgreSQL, no vector usage).
-- Jobs are append-mostly, and the listing pages (created_at, id) ascending per Team.
CREATE INDEX ix_knowledge_index_job_team_listing
    ON crewscope.knowledge_index_job (organization_id, team_id, created_at, id);
