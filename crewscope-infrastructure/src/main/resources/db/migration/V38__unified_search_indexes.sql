-- Search remains a derived query over authoritative tables. These GIN indexes accelerate the
-- bounded ILIKE predicates without creating a second search index or duplicating domain facts.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_work_item_search_title
    ON crewscope.work_item USING gin (title gin_trgm_ops);

CREATE INDEX ix_conversation_search_title
    ON crewscope.conversation USING gin (title gin_trgm_ops);

CREATE INDEX ix_principal_search_display_name
    ON crewscope.principal USING gin (display_name gin_trgm_ops);

CREATE INDEX ix_repository_binding_search_key
    ON crewscope.repository_binding USING gin (repository_key gin_trgm_ops);
