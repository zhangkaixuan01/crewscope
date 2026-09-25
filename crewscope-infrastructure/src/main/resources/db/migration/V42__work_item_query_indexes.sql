-- M9b-A06: stable server-side ordering for the WorkItem list read model.
--
-- The default updatedAt DESC, id DESC traversal is the hot path for the board, list and Today;
-- this covering-key index serves it from one btree descent per page. The priority and dueAt
-- orderings are lower-traffic and fall back to this index's org/team/project prefix plus a sort;
-- V43 adds dedicated indexes only if EXPLAIN in the A06 scale fixtures proves a regression.
CREATE INDEX ix_work_item_project_updated
    ON crewscope.work_item (organization_id, team_id, project_id, updated_at DESC, id DESC);
