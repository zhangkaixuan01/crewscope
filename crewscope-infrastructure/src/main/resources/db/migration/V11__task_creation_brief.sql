-- M3-A01 pins the executable objective and acceptance criteria instead of relying on mutable
-- WorkItem text. Pre-A01 rows are backfilled from their current source WorkItem during upgrade.

ALTER TABLE crewscope.task
    ADD COLUMN objective TEXT,
    ADD COLUMN acceptance_criteria JSONB;

UPDATE crewscope.task task
SET objective = item.title,
    acceptance_criteria = CASE
        WHEN item.description IS NULL OR BTRIM(item.description) = '' THEN '[]'::jsonb
        ELSE jsonb_build_array(item.description)
    END
FROM crewscope.work_item item
WHERE item.organization_id = task.organization_id
  AND item.team_id = task.team_id
  AND item.workspace_id = task.workspace_id
  AND item.project_id = task.project_id
  AND item.id = task.work_item_id;

ALTER TABLE crewscope.task
    ALTER COLUMN objective SET NOT NULL,
    ALTER COLUMN acceptance_criteria SET NOT NULL,
    ADD CONSTRAINT ck_task_objective CHECK (
        BTRIM(objective) <> '' AND CHAR_LENGTH(objective) <= 10000
    ),
    ADD CONSTRAINT ck_task_acceptance_criteria CHECK (
        jsonb_typeof(acceptance_criteria) = 'array'
        AND jsonb_array_length(acceptance_criteria) <= 100
    );
