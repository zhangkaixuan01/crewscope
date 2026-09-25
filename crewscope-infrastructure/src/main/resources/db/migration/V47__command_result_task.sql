-- A05: a delegation command stores its own TASK coordinate (team + project
-- scope, resource = the created Task) so an unknown-outcome retry lands on the
-- exact Task through command-results instead of delegating twice.
ALTER TABLE crewscope.command_result DROP CONSTRAINT ck_command_result_coordinate;
ALTER TABLE crewscope.command_result ADD CONSTRAINT ck_command_result_coordinate CHECK (
    (resource_type = 'WORK_PROJECT' AND project_id IS NOT NULL AND project_id = resource_id)
    OR (resource_type = 'WORK_ITEM' AND project_id IS NOT NULL)
    OR (resource_type = 'TASK' AND project_id IS NOT NULL)
    OR (resource_type = 'CONVERSATION' AND project_id IS NULL)
    OR (resource_type = 'TEAM_MEMBER' AND project_id IS NULL)
);
