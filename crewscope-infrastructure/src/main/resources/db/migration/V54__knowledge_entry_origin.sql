-- M10-A02b: immutable distillation attribution on the knowledge head (D3).
-- The origin pair is either fully present or fully NULL, survives every lifecycle
-- command (the UPDATE path never touches these columns) and marks distilled drafts
-- for the command-level disclosure check before publication. It is deliberately a
-- soft cross-aggregate reference: no FK to task_execution, mirroring the V52 policy
-- that keeps the knowledge aggregate independently replayable. The partial index
-- answers "which entries were distilled from this execution" without widening the
-- hot team listing.

ALTER TABLE crewscope.knowledge_entry
    ADD COLUMN source_task_execution_id UUID,
    ADD COLUMN source_execution_attempt INTEGER;

ALTER TABLE crewscope.knowledge_entry
    ADD CONSTRAINT ck_knowledge_entry_origin_pair
        CHECK (
            (source_task_execution_id IS NULL AND source_execution_attempt IS NULL)
            OR (
                source_task_execution_id IS NOT NULL
                AND source_execution_attempt IS NOT NULL
                AND source_execution_attempt >= 1
            )
        );

CREATE INDEX ix_knowledge_entry_source_execution
    ON crewscope.knowledge_entry (organization_id, team_id, source_task_execution_id)
    WHERE source_task_execution_id IS NOT NULL;
